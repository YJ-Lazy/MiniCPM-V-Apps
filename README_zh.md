# MiniCPM-V-Apps — 本仓库修改说明

[English](README.md) | **中文**

本页面仅汇总当前仓库相对原项目新增或调整的内容，并提供原项目文档及本仓库实现文档的跳转入口。原项目 README 已完整保留在其他位置，没有删除原有说明。

## 原项目文档

- [原项目英文 README](docs/original/README.md) — 完整的平台介绍、构建步骤及模型说明。
- [原项目中文 README](docs/original/README_zh.md) — 完整中文项目指南、平台构建步骤及模型说明。
- [下载与安装说明（英文）](DOWNLOAD.md)
- [下载与安装说明（中文）](DOWNLOAD_zh.md)

## 本仓库修改内容

### Android VoxCPM2 语音合成

- 增加 Android VoxCPM2 TTS 页面。
- 增加 WAV 音频导出，通过 Android 系统文件选择器保存到用户指定位置。
- 增加本地 API 设置入口，并通过前台服务运行 API。

### Android 本地 HTTP API

- 增加仅监听本机回环地址的 API：`http://127.0.0.1:8765`，供同一台 Android 设备上的程序调用。
- 增加 Bearer Token 身份验证及异步任务式语音生成。
- 实现普通语音合成、参考音频克隆、任务状态查询和生成音频获取接口。
- 补充 API 使用方式、安全边界及当前实现限制说明。

## 实现与使用文档

- [Android TTS、WAV 导出与本地 API 说明](docs/ANDROID_LOCAL_API_PLAN.md)
- [TTS 页面源码](MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/TtsActivity.kt)
- [Local API 源码](MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/LocalApi.kt)
- [Android 构建工作流](.github/workflows/android-build.yml)

> 根目录 README 现在只承担“修改内容摘要 + 文档导航”的作用。原项目完整 README 已归档至 `docs/original/`，方便继续查阅，也避免覆盖原有项目说明。
