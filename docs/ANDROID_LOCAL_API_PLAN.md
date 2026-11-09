# Android VoxCPM2 Local API — implementation notes

The Android app now contains an initial on-device VoxCPM2 HTTP API implementation and WAV export from the TTS screen. This document describes the implemented behavior and distinguishes it from future work.

## User-facing features

- TTS screen: generate speech with the on-device VoxCPM2 engine and play the resulting WAV.
- WAV export: use Android's Storage Access Framework save dialog to copy the generated WAV to a user-selected location.
- Local API settings: open from the TTS screen, display/copy/regenerate a random Bearer token, start/stop the API foreground service.
- The API shares the app's `TtsEngine`; it does not create a second native model runtime.

## Runtime and security

- Base URL: `http://127.0.0.1:8765`.
- The server binds explicitly to IPv4 loopback. It is intended for clients on the same Android device, not LAN clients.
- `GET /health` is unauthenticated for readiness checks. All other routes require `Authorization: Bearer <token>`.
- The token is stored in app-private preferences. Regenerating it invalidates the token currently held by an already-running server only after that server is restarted; restart the API service after rotating the token.
- The service runs in the foreground with a persistent notification and a Stop action.
- Generated audio and temporary clone reference WAV files are stored under app-private files.

## HTTP endpoints

| Method | Path | Authentication | Behavior |
|---|---|---|---|
| GET | `/health` | None | Returns service/model readiness |
| GET | `/v1/models` | Bearer | Lists `voxcpm2` |
| POST | `/v1/audio/speech` | Bearer | Queues text-to-speech |
| POST | `/v1/audio/clone` | Bearer | Queues speech using Base64 reference WAV |
| GET | `/v1/tasks/{id}` | Bearer | Returns `queued`, `running`, `completed`, or `failed` |
| GET | `/v1/tasks/{id}/audio` | Bearer | Returns completed WAV bytes |

### Speech JSON

```json
{
  "model": "voxcpm2",
  "input": "Hello from the local VoxCPM2 API.",
  "cfg_value": 2.0,
  "timesteps": 5
}
```

### Clone JSON

```json
{
  "model": "voxcpm2",
  "input": "Text to speak in the reference voice.",
  "cfg_value": 2.0,
  "timesteps": 5,
  "reference_audio_base64": "BASE64_ENCODED_PCM16_WAV"
}
```

The clone reference must be a RIFF/WAVE PCM16 file, at least a basic 44-byte WAV header, and no larger than 8 MiB. The TTS screen's reference-audio import follows the native reader's PCM16 mono/stereo constraint.

## Current implementation limits

- Port is fixed at `8765`; the UI currently displays the port but does not offer port editing.
- Request bodies are limited to 10 MiB; text input is limited to 5,000 characters.
- Clone reference audio is supplied as Base64 JSON, not multipart form data or a remote file path.
- The HTTP parser currently expects a `Content-Length` header; chunked transfer encoding is not implemented.
- The task registry is in memory. Tasks are not restored after process death, and automatic task/audio retention cleanup is not yet implemented.
- The API serializes inference through a mutex because the native engine is shared and must not run concurrent generations.
- The endpoint is loopback HTTP, not TLS. Keep the token private; do not forward the port or bind the server to a public interface.
- This is a local integration API with asynchronous task handling, not a complete OpenAI API compatibility layer. Parameters such as `voice` and `response_format` are not currently implemented.

## Build and device validation

Build the Android debug APK from the repository root:

```bash
cd MiniCPM-V-demo-Android
./gradlew assembleDebug
```

A successful CI build confirms compilation and APK packaging only. Runtime validation still needs to cover model loading, normal speech generation, clone generation with a real PCM16 WAV, task polling, WAV download, token rotation, foreground-service lifecycle, background execution, and device-specific memory/thermal behavior.
