---
Task ID: 1
Agent: Main
Task: Fix streaming viewer showing black screen in dashboard

Work Log:
- Analyzed 3 user screenshots showing: waiting → connected → error states
- Examined server.py streaming endpoints (/api/stream/frame, /ws/stream, /ws/stream/viewer)
- Examined Android streaming services (ScreenStreamService, CameraStreamService, AudioStreamService)
- Found root causes:
  1. Android streaming services use H264/H265 (not displayable in browser <img>)
  2. WebSocket URL in Android is wrong (missing /ws/stream path and device_id query params)
  3. Screenshot upload to /api/upload fails (missing device_id in multipart form)
  4. Screenshot command result has truncated base64 (500 chars) - not enough to display
  5. Server IP identification failed through reverse proxy (127.0.0.1 instead of real IP)

- Implemented solution:
  1. Created JPEG screenshot-based streaming system on server (no Android rebuild needed)
  2. Fixed /api/upload to accept uploads without device_id, identify device by IP
  3. Added _get_real_ip() helper for reverse proxy header support (X-Forwarded-For, X-Real-IP)
  4. Added IP tracking in heartbeat and command poll handlers
  5. Added POST /api/stream/jpeg_start - starts auto-screenshot loop (queues screenshot commands every N seconds)
  6. Added POST /api/stream/jpeg_stop - stops auto-screenshot loop
  7. Modified api_stream_frame to check both WebSocket cache and module-level upload cache
  8. Modified Firebase result listener to cache screenshot/camera results with full base64 in _latest_frames_module
  9. Rewrote dashboard JS to use JPEG streaming (removed WebSocket, uses REST polling)
  10. Fixed Android ControlExecutor.takeScreenshot() to return full base64 (not truncated)
  11. Fixed Android takePhotoReal() to return full base64
  12. Fixed Android ApiClient.uploadFile() to include device_id in multipart form

- Deployed server to production successfully

Stage Summary:
- Server-side streaming infrastructure is complete and deployed
- Server now caches images from: (a) file uploads via /api/upload, (b) Firebase command results with base64
- Dashboard uses JPEG polling approach instead of WebSocket/H264
- Android source code fixed but APK NOT rebuilt (no Android SDK available)
- Streaming will work once the Android APK is rebuilt and installed with the fixes
- The Firebase result path should also work once the app sends full base64 (no truncation)