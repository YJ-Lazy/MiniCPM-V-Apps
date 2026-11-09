# MiniCPM-V-Apps — Repository Changes

**English** | [中文](README_zh.md)

This fork keeps its project-specific changes and implementation notes here. The original project guides are preserved separately and remain available through the links below.

## Original project documentation

- [Original English README](docs/original/README.md) — complete upstream project guide, including platform setup, build instructions and model documentation.
- [原项目中文 README](docs/original/README_zh.md) — 完整中文项目说明、平台构建步骤与模型文档。
- [Download and installation guide](DOWNLOAD.md)
- [中文下载与安装说明](DOWNLOAD_zh.md)

## Changes in this repository

### Android VoxCPM2 TTS

- Added an Android VoxCPM2 text-to-speech screen.
- Added WAV audio export through Android's system file picker.
- Added local API settings and a foreground service for on-device API access.

### Android local HTTP API

- Added a loopback-only API at `http://127.0.0.1:8765` for applications running on the same Android device.
- Added Bearer-token authentication and task-based speech generation.
- Implemented speech generation, reference-audio cloning, task status and generated-audio retrieval endpoints.
- Documented current API behavior, security boundaries and implementation limitations.

## Implementation and usage

- [Android TTS, WAV export and local API guide](docs/ANDROID_LOCAL_API_PLAN.md)
- [TTS screen source](MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/TtsActivity.kt)
- [Local API source](MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/LocalApi.kt)
- [Android build workflow](.github/workflows/android-build.yml)

> The original project documentation has not been discarded or replaced with a shortened copy. It is preserved in `docs/original/`; this root README is intentionally a change summary and navigation page.
