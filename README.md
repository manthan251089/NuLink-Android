# Nu-Link Android — Milestone 2 (USB diagnostics)

This project builds a debug APK through GitHub Actions.

## Included
- USB OTG detection for VID `0416`, PID `511C`
- Android USB permission handling
- USB interface and endpoint descriptor display
- Claim/release of interface 1 and confirmation of bulk endpoints `0x83 IN` and `0x04 OUT`
- Intel HEX parsing and checksum validation
- Java/Kotlin JVM target aligned to Java 17
- GitHub Actions workflow to build `app-debug.apk`

## Not yet implemented
This build does **not** send Nu-Link protocol commands and does not identify the attached MCU. MCU identification, erase, programming, verify, and offline transfer remain disabled until the protocol commands and response framing are independently validated from the Windows ICP captures.

## Build
Push this project to GitHub (including `.github/workflows/android.yml`), then open **Actions**, run **Build Nu-Link Android APK**, and download the `NuLink-Android-debug-APK` artifact. Extract `app-debug.apk` from the downloaded ZIP.
