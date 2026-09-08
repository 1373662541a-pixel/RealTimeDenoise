package com.realtimedenoise.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private AudioPipeline pipeline;
    private Button btnStart, btnStop;
    private CheckBox cbMonitor, cbSave;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pipeline = new AudioPipeline();
        buildUi();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
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
        root.addView(cbMonitor);
        root.addView(cbSave);
        root.addView(btnStart);
        root.addView(btnStop);
        setContentView(root);
    }

    private void doStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_SHORT).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        File out = null;
        if (cbSave.isChecked()) {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            out = new File(dir, "denoise_" + name + ".wav");
        }
        try {
            pipeline.start(cbMonitor.isChecked(), cbSave.isChecked(), out);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            cbMonitor.setEnabled(true);
            cbSave.setEnabled(true);
            tvStatus.setText(cbSave.isChecked() ? "运行中…   保存至: " + out : "运行中…（监听中）");
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void doStop() {
        pipeline.stop();
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        tvStatus.setText("已停止");
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        pipeline.stop();
        super.onDestroy();
    }
}