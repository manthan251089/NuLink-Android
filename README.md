# Nu-Link Android — Milestone 4: Captured Auto Detect test (upload refresh)

This project builds a debug APK through GitHub Actions.

## What this build does
- Detects the user's Nu-Link clone (`VID 0416`, `PID 511C`).
- Requests Android USB permission and claims interface 1.
- Verifies bulk IN `0x83` and OUT `0x04`.
- Adds an **AUTO DETECT MCU (MS51PC0AE)** action.
- Replays the 435 OUT bulk frames extracted from the user's Windows capture `NuLink_MS51PC0AE_Detect_Only.pcapng`.
- Reads one 64-byte IN response for each OUT transaction and compares it with the matching response in that capture.
- Reports `MS51PC0AE — capture reference matched` only if every response exactly matches the captured Windows reference. Otherwise it reports that the MCU is not confirmed.

## Important limitation
This is a controlled, capture-derived Auto Detect replay for the MS51PC0AE test case. It is not yet a general implementation of the Nu-Link protocol and does not support MS51FB9AE detection in this build. The packet sequence was extracted from the capture that the user confirmed was made in Auto Detection mode only. It does not include erase, flash write, configuration-bit, or lock-bit operations. Do not use this APK as a production programmer.

Exact matching is intentionally strict. Firmware/response variations may cause a false negative; in that case the app reports "MCU not confirmed" rather than guessing.

## Build with GitHub Actions
1. Upload the full extracted project to the GitHub repository, including `.github/workflows/android.yml`.
2. Open **Actions** and run the workflow.
3. Download `NuLink-Android-debug-APK` from the workflow artifacts.
4. Extract and install `app-debug.apk`.

## Protocol evidence
The embedded binary assets contain 435 paired 64-byte bulk OUT/IN frames from the supplied MS51PC0AE detect-only capture. The final OUT record was excluded because no corresponding IN response was present in the capture.


## Re-upload note (version 0.4)
This refresh increments the Android version to 0.4 (versionCode 4) so GitHub will recognize a real source change when these files replace the older project. Upload the CONTENTS of this folder into the repository root, not the outer folder. Ensure the hidden `.github` directory is included. Commit the changes before running Actions.
