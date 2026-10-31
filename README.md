# Nu-Link Android — Milestone 6: Full response diagnostics (v0.6)

This Android project builds a debug APK through GitHub Actions.

## What this build does
- Detects the Nu-Link clone (VID 0416, PID 511C), claims interface 1, and uses bulk IN 0x83 / OUT 0x04.
- Replays the same 435 captured MS51PC0AE Auto Detect request frames used in v0.5. No new protocol commands are added.
- Compares each live 64-byte response against the matching response from the user's known-good Windows capture.
- Reports every mismatching transaction, including its 64-byte OUT request, expected IN response, actual IN response, differing byte offsets, and expected/actual byte values.
- Reports total differing response bytes and elapsed time.
- Provides a button to copy the complete diagnostic report to the Android clipboard.
- Continues to require an exact full-sequence match before displaying the reference-match result.

## Safety / limitations
This is a capture-derived diagnostic replay for the MS51PC0AE test case, not a general Nu-Link protocol implementation. It does not implement erase, flash programming, configuration-bit, or lock-bit operations. Do not use it as a production programmer. A full reference match means the exchange matches the supplied capture; it is not independent silicon authentication.

## Build APK with GitHub Actions
1. Extract this ZIP.
2. In the existing repository, choose **Add file → Upload files**.
3. Upload the CONTENTS of this folder to the repository root, including the hidden `.github` folder.
4. Commit the changes (versionCode 6 / versionName 0.6 are changed).
5. Open **Actions** and run **Build Nu-Link Android APK**.
6. Download the `NuLink-Android-debug-APK` artifact and extract `app-debug.apk`.
