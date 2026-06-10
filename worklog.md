---
Task ID: 1
Agent: Main Agent
Task: Fix streaming crash, audio/camera not reaching dashboard, add missing permissions

Work Log:
- Analyzed root causes: (1) Screen crash: FOREGROUND_SERVICE_MEDIA_PROJECTION not declared, manifest type mismatch, (2) Audio/Camera "stream ended": connectToServer() blocked streaming start, WebSocket endpoint missing on server, (3) Missing permissions not requested at runtime
- Fixed AndroidManifest.xml: Added FOREGROUND_SERVICE_MEDIA_PROJECTION and FOREGROUND_SERVICE_CONNECTED_DEVICE permissions, changed ScreenStreamService to mediaProjection|specialUse, AudioStreamService to microphone|mediaProjection|specialUse
- Rewrote ScreenStreamService.startStreaming(): Moved VirtualDisplay creation and isStreaming.set(true) BEFORE WebSocket connection, made connectToServer() non-blocking with background retries
- Rewrote AudioStreamService.startStreaming(): Moved recording start and isStreaming.set(true) BEFORE WebSocket connection, made connectToServer() non-blocking with background retries
- Rewrote CameraStreamService.startStreaming(): Moved camera open and isStreaming.set(true) BEFORE WebSocket connection, made connectToServer() non-blocking with background retries
- Added WebSocket endpoint /ws/stream on server.py: receives frames from devices, stores latest frames, forwards to dashboard viewers
- Added /ws/stream/viewer endpoint: dashboard WebSocket clients connect and receive frames
- Added REST endpoints: /api/stream/frame/{device_id}, /api/stream/status, /api/stream/start, /api/stream/stop
- Added 8 missing permissions to PermissionChecker and PermissionActivity: phone+call log, SMS, calendar, body sensors, activity recognition, nearby devices, all files access
- Added SPECIAL_ALL_FILES permission type for MANAGE_EXTERNAL_STORAGE
- Added requestAllFilesAccess() method to PermissionActivity
- Committed and pushed to GitHub (commit ad1077a)
- Triggered GitHub Actions build (HTTP 204)

Stage Summary:
- 7 files modified, 452 insertions, 59 deletions
- All streaming services now start recording immediately, server connection is async
- Server has WebSocket support for streaming
- 8 new runtime permissions added to permission system
- APK build triggered on GitHub Actions
