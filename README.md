# RealTimeDenoise 实时降噪监听

Android 上的实时音频降噪监听工具：边采集麦克风声音，边实时去除低频轰鸣/背景底噪并增强人声，同时通过耳机或外放实时监听，并可保存处理后的干净录音。

## 功能
- **实时链路**：`AudioRecord` 麦克风(48kHz 单声道) → 20ms 分块 DSP → `AudioTrack` 实时监听输出
- **降噪 / 人声增强**：85Hz 高通去低频轰鸣 + 多频段人声均衡增强 + 软限幅(0.92)
- **输出路由**：耳机 / 外放（取决于系统当前输出）都能监听
- **可选保存**：处理后音频以 WAV 保存到 App 外部目录
- 纯 Java 实现，无第三方依赖、无 NDK

## 构建
GitHub Actions 自动构建（`.github/workflows/build.yml`），推送 main 分支后生成 `app-debug.apk` 供安装。

手动：
```bash
./gradlew assembleDebug
```
APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 参数
DSP 链（对应校准 v2）：
- 高通 85Hz（削弱 ~48-50Hz 电源哼声及极低频轰鸣）
- 峰值均衡：200Hz +2.5dB / 700Hz +3.5dB / 2300Hz +4.5dB / 5000Hz +2dB
- 软限幅 limit=0.92

参数在 `DenoiseDsp.java` 中可调。

## 注意
- 扬声器监听时请控制音量，避免声反馈啸叫；耳机监听效果更佳。
- 首次启动需授予录音权限。
