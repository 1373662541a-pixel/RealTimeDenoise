package com.realtimedenoise.app;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time pipeline:
 *   AudioRecord mic -> 20ms blocks -> DenoiseDsp -> AudioTrack monitor (+ optional WAV save)
 * 48000Hz, mono, PCM 16-bit.
 */
public class AudioPipeline {

    private static final int SAMPLE_RATE = 48000;
    private static final int BLOCK = SAMPLE_RATE * 20 / 1000; // 20ms = 960 samples

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean monitorOn = new AtomicBoolean(true);
    private final AtomicBoolean saveOn = new AtomicBoolean(false);

    private DenoiseDsp dsp;
    private AudioRecord record;
    private AudioTrack track;
    private Thread thread;
    private RandomAccessFile wav;
    private int wavDataBytes;

    public void start(boolean monitor, boolean save, File outFile) throws Exception {
        int minRec = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minRec * 2);

        int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minTrack, BLOCK * 2) * 2;
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufSize, AudioTrack.MODE_STREAM);

        if (record.getState() != AudioRecord.STATE_INITIALIZED
                || track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("Audio device not init (mic or output)");
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
        // placeholder 44-byte header
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