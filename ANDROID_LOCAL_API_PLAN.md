# Android Local VoxCPM2 API Implementation Plan

This document records the next implementation phase. The current patch only adds reference-audio import and fixes TTS screen system-bar insets; it does not start an HTTP server yet.

## Goals

- Expose the already-loaded VoxCPM2 engine to other apps on the same Android device.
- Keep inference fully on-device; no cloud service or Python runtime.
- Reuse the singleton `TtsEngine` and its single-thread inference dispatcher.
- Preserve the existing interactive TTS screen and recording/preset workflows.

## Proposed architecture

`LocalApiForegroundService` -> `LocalApiServer` -> `ApiTaskManager` -> shared `TtsEngine` -> JNI / llama.cpp-omni.

The service owns API lifecycle and a partial wake lock while a generation task is active. The server must not create or initialize a second `TtsEngine` instance. The API task manager serializes inference and stores generated WAV files in app-private storage.

## Initial endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Service and model readiness |
| GET | `/v1/models` | Report `voxcpm2` when available |
| POST | `/v1/audio/speech` | Generate WAV from text |
| POST | `/v1/audio/clone` | Generate WAV using uploaded/reference audio |
| GET | `/v1/tasks/{id}` | Query task state |
| GET | `/v1/tasks/{id}/audio` | Retrieve generated WAV |

`/v1/audio/speech` should accept OpenAI-style `model`, `input`, `voice`, and `response_format`, plus VoxCPM2-specific `cfg_value` and `timesteps`. Long generation should return a task ID rather than hold an HTTP connection open indefinitely. A later compatibility mode may return the WAV directly for short requests.

## Security and Android constraints

- Default bind address: `127.0.0.1` only. Do not bind `0.0.0.0` by default.
- Require a randomly generated Bearer token; show/copy/regenerate it in the app's API settings.
- Add request body size limits, text length limits, task queue limits, and per-request timeouts.
- Do not expose arbitrary filesystem paths. Accept reference audio as multipart upload or a managed reference-audio ID.
- Add a foreground service notification while API mode is enabled, with an explicit Stop action.
- Check Android background-start restrictions and notification permission on Android 13+.
- Use one inference queue because the current native runtime is a singleton and is not safe for parallel generation.

## Implementation sequence

1. Add API settings UI (enable/disable, port, token, endpoint display, copy buttons).
2. Add the foreground service and lifecycle-safe shared engine access.
3. Add a lightweight embedded HTTP server and strict JSON/multipart parsing.
4. Add task queue, task status, generated-file retention and cleanup.
5. Add endpoint tests and a sample client (curl/Kotlin).
6. Validate on a physical ARM64 phone: app backgrounding, screen-off behavior, repeated requests, memory, temperature, and recovery after service restart.

## Current native audio constraint

`omni_jni.cpp` currently reads reference WAV files using a minimal 44-byte header parser and 16-bit PCM samples. The import UI therefore validates standard PCM16 mono/stereo WAV files. Supporting MP3/AAC or WAV files with extended chunks requires adding a proper Android media decoder or a robust WAV parser before claiming broad audio-format support.
