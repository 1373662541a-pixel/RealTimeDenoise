package com.realtimedenoise.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time pipeline:
 *   AudioRecord (mic OR system playback capture) -> 20ms blocks -> DenoiseDsp
 *     -> AudioTrack monitor (+ optional WAV save)
 * 48000Hz, mono, PCM 16-bit.
 *
 * The monitor AudioTrack is built with ALLOW_CAPTURE_BY_NONE so that when we are
 * capturing "other apps' playback" we do NOT re-capture our own denoised output
 * (which would cause a feedback loop).
 */
public class AudioPipeline {

    public static final int SAMPLE_RATE = 48000;
    public static final int BLOCK = SAMPLE_RATE * 20 / 1000; // 20ms = 960 samples

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean monitorOn = new AtomicBoolean(true);
    private final AtomicBoolean saveOn = new AtomicBoolean(false);

    private DenoiseDsp dsp;
    private AudioRecord record;
    private AudioTrack track;
    private Thread thread;
    private RandomAccessFile wav;
    private int wavDataBytes;

    // AGC: faint system-capture sources (e.g. ~-60dBFS) are inaudible, so we
    // normalize level toward a comfortable target before the DSP chain.
    private volatile boolean agcOn = false;
    private double agcGain = 1.0;

    public void start(AudioRecord source, boolean monitor, boolean save, File outFile) throws Exception {
        record = source;

        int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minTrack, BLOCK * 2) * 2;

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat outFmt = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(outFmt)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        if (record.getState() != AudioRecord.STATE_INITIALIZED
                || track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("Audio device not init (source or output)");
        }

        if (save && outFile != null) {
            openWav(outFile);
        }

        dsp = new DenoiseDsp(SAMPLE_RATE);
        monitorOn.set(monitor);
        saveOn.set(save && outFile != null);
        running.set(true);
        record.startRecording();
        track.play();

        thread = new Thread(this::loop, "AudioPipeline");
        thread.start();
    }

    /** Enable AGC (used for faint system-capture input). Should be called before start(). */
    public void setAutoGain(boolean on) {
        agcOn = on;
        agcGain = 1.0;
    }

    /**
     * Simple per-block AGC: smooth the peak toward a target so a very quiet
     * captured source becomes clearly audible without clipping. Only boost.
     */
    private void runAgc(float[] buf, int len) {
        double peak = 0.0;
        for (int i = 0; i < len; i++) {
            double a = Math.abs(buf[i]);
            if (a > peak) peak = a;
        }
        final double target = 0.45;              // aim peaks near -7dBFS
        final double maxGain = 200.0;            // up to ~+46dB
        double desired = target / (peak + 1e-9);
        if (desired < 1.0) desired = 1.0;        // only boost, never attenuate
        if (desired > maxGain) desired = maxGain;
        agcGain += (desired - agcGain) * 0.08;   // smooth over ~250ms
        double g = agcGain;
        for (int i = 0; i < len; i++) buf[i] = (float) (buf[i] * g);
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
        }
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
            record = null;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) {}
            track.release();
            track = null;
        }
        finishWav();
    }

    private void loop() {
        short[] in = new short[BLOCK];
        short[] out = new short[BLOCK];
        float[] flt = new float[BLOCK];
        while (running.get()) {
            int n = record.read(in, 0, BLOCK);
            if (n <= 0) continue;
            for (int i = 0; i < n; i++) flt[i] = in[i] / 32768.0f;
            if (agcOn) runAgc(flt, n);
            dsp.process(flt, n);
            for (int i = 0; i < n; i++) out[i] = (short) (Math.max(-1f, Math.min(1f, flt[i])) * 32767f);
            if (monitorOn.get()) {
                track.write(out, 0, n);
            }
            if (saveOn.get()) {
                writeWav(out, n);
            }
        }
    }

    private void openWav(File f) throws Exception {
        wav = new RandomAccessFile(f, "rw");
        wav.setLength(0);
        byte[] h = new byte[44];
        wav.write(h);
        wavDataBytes = 0;
    }

    private void writeWav(short[] data, int n) {
        try {
            byte[] b = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                b[i * 2] = (byte) (data[i] & 0xff);
                b[i * 2 + 1] = (byte) ((data[i] >> 8) & 0xff);
            }
            wav.write(b);
            wavDataBytes += b.length;
        } catch (Exception ignored) {}
    }

    private void finishWav() {
        if (wav == null) return;
        try {
            int byteRate = SAMPLE_RATE * 2; // 16-bit mono
            wav.seek(0);
            wav.write("RIFF".getBytes());
            wav.write(le32(36 + wavDataBytes));
            wav.write("WAVE".getBytes());
            wav.write("fmt ".getBytes());
            wav.write(le32(16));
            wav.write(le16(1));            // PCM
            wav.write(le16(1));            // mono
            wav.write(le32(SAMPLE_RATE));
            wav.write(le32(byteRate));
            wav.write(le16(2));            // block align
            wav.write(le16(16));           // bits
            wav.write("data".getBytes());
            wav.write(le32(wavDataBytes));
            wav.close();
        } catch (Exception ignored) {}
        wav = null;
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }
    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}