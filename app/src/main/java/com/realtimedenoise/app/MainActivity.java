package com.realtimedenoise.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 1001;
    private static final int REQ_MP = 2001;

    private AudioPipeline pipeline;
    private MediaProjectionManager mpManager;
    private MediaProjection activeProjection;
    private int mpResultCode;
    private Intent mpResultData;

    private Button btnStart, btnStop;
    private CheckBox cbMonitor, cbSave;
    private TextView tvStatus;
    private RadioGroup rgSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pipeline = new AudioPipeline();
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        tvStatus = new TextView(this);
        tvStatus.setText("实时降噪监听\n（去低频轰鸣 + 人声增强）");
        tvStatus.setTextSize(18);
        tvStatus.setPadding(0, 0, 0, pad);

        rgSource = new RadioGroup(this);
        rgSource.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbMic = new RadioButton(this);
        rbMic.setId(1);
        rbMic.setText("输入源：手机麦克风");
        RadioButton rbSys = new RadioButton(this);
        rbSys.setId(2);
        rbSys.setText("输入源：其他应用正在播放的声音（实验·需授权）");
        rgSource.addView(rbMic);
        rgSource.addView(rbSys);
        rbMic.setChecked(true);

        cbMonitor = new CheckBox(this);
        cbMonitor.setText("监听输出（耳机/外放）");
        cbMonitor.setChecked(true);

        cbSave = new CheckBox(this);
        cbSave.setText("保存处理后的录音 (WAV)");
        cbSave.setChecked(false);

        btnStart = new Button(this);
        btnStart.setText("开始");
        btnStart.setOnClickListener(v -> doStart());

        btnStop = new Button(this);
        btnStop.setText("停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> doStop());

        root.addView(tvStatus);
        root.addView(rgSource);
        root.addView(cbMonitor);
        root.addView(cbSave);
        root.addView(btnStart);
        root.addView(btnStop);
        setContentView(root);

        rgSource.setOnCheckedChangeListener((g, id) -> {
            if (id == 2) {
                tvStatus.setText("实验模式：捕获其他应用正在播放的声音。\n请先让龙势云开始播放，再点“开始”，\n并在系统弹窗中允许“录制屏幕/声音”。");
            } else {
                tvStatus.setText("实时降噪监听\n（手机麦克风输入）");
            }
        });
    }

    private void doStart() {
        boolean capture = rgSource.getCheckedRadioButtonId() == 2;

        // both input modes need RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }

        if (capture) {
            if (Build.VERSION.SDK_INT < 29) {
                toast("系统声音捕获需要 Android 10+");
                return;
            }
            if (mpResultData == null) {
                mpResultCode = 0;
                mpResultData = null;
                try {
                    startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_MP);
                    tvStatus.setText("请在系统弹窗中允许“录制屏幕/声音”，授权后自动开始。");
                } catch (Exception e) {
                    toast("无法发起系统捕获授权: " + e.getMessage());
                }
                return;
            }
            try {
                activeProjection = mpManager.getMediaProjection(mpResultCode, mpResultData);
                AudioRecord src = buildCaptureRecord(activeProjection);
                startInternal(src, true);
            } catch (Exception e) {
                toast("系统声音捕获启动失败: " + e.getMessage());
                if (activeProjection != null) { try { activeProjection.stop(); } catch (Exception ignored) {} activeProjection = null; }
                mpResultData = null;
            }
            return;
        }

        // mic source
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        try {
            startInternal(buildMicRecord(), false);
        } catch (Exception e) {
            toast("启动失败: " + e.getMessage());
        }
    }

    private void startInternal(AudioRecord record, boolean capture) throws Exception {
        File out = null;
        if (cbSave.isChecked()) {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            out = new File(dir, (capture ? "capture_" : "denoise_") + name + ".wav");
        }
        pipeline.start(record, cbMonitor.isChecked(), cbSave.isChecked(), out);
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        tvStatus.setText(cbSave.isChecked() ? "运行中… 保存至: " + out : "运行中…（监听中）");
    }

    private AudioRecord buildMicRecord() throws Exception {
        int min = AudioRecord.getMinBufferSize(AudioPipeline.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, AudioPipeline.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, AudioPipeline.BLOCK * 2) * 2);
        if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("麦克风初始化失败");
        return r;
    }

    private AudioRecord buildCaptureRecord(MediaProjection mp) throws Exception {
        AudioFormat af = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioPipeline.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        int min = AudioRecord.getMinBufferSize(AudioPipeline.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord.Builder()
                .setAudioFormat(af)
                .setBufferSizeInBytes(Math.max(min, AudioPipeline.BLOCK * 2) * 2)
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("捕获初始化失败（可能授权被拒或源不可捕获）");
        }
        return r;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MP) {
            if (resultCode == RESULT_OK && data != null) {
                mpResultCode = resultCode;
                mpResultData = data;
                doStart();
            } else {
                tvStatus.setText("已取消系统捕获授权");
                toast("未获得系统捕获授权");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) doStart();
            else toast("需要录音权限");
        }
    }

    private void doStop() {
        pipeline.stop();
        if (activeProjection != null) {
            try { activeProjection.stop(); } catch (Exception ignored) {}
            activeProjection = null;
        }
        mpResultData = null;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        tvStatus.setText("已停止");
        toast("已停止");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        pipeline.stop();
        if (activeProjection != null) {
            try { activeProjection.stop(); } catch (Exception ignored) {}
            activeProjection = null;
        }
        super.onDestroy();
    }
}