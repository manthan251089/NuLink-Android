# Nu-Link Android ICP — research starter

This is a native Kotlin/Android Studio starter for the user's Nu-Link-compatible clone:
- VID:PID `0416:511C`
- USB interface 0: HID interrupt endpoints `0x81` IN / `0x02` OUT
- USB interface 1: bulk endpoints `0x83` IN / `0x04` OUT
- target MCUs: MS51PC0AE and MS51FB9AE

## Current functionality
- Detects the USB device and requests Android USB permission.
- Attempts to claim interface 1 and locates the two bulk endpoints.
- Lets the user select an Intel HEX file and validates record lengths/checksums/EOF.
- Reports HEX data size and address range.

## Deliberately not implemented yet
No erase/program/verify/offline command is sent. The command protocol must be decoded and validated from the supplied USBPcap traces before enabling destructive operations. The user's captures show successful Windows programming, but a USB descriptor alone does not define the command protocol.

## Build
Open this folder in Android Studio (Giraffe or newer), let Gradle sync, then Build > Build Bundle(s) / APK(s) > Build APK(s).
Use an Android phone with USB host/OTG support. Close the Windows ICP tool before connecting the programmer to Android.

## Next protocol engineering work
1. Decode USBPcap transfer records and reconstruct bulk OUT/IN transactions.
2. Compare auto-detect, manual MS51PC0AE, MS51FB9AE, online program, and offline-transfer traces.
3. Identify command framing, sequence IDs, response/status codes and data chunking.
4. Implement read-only version/device-identification commands first.
5. Add erase/program/verify only after protocol responses and target memory geometry are validated.
6. Add offline transfer and button workflow last.

This is a development starter, not a finished programmer APK.

## Build an APK without installing Android Studio (GitHub Actions)

1. Create a free GitHub account and a new repository.
2. Extract this ZIP on your PC.
3. Upload all files and folders in this project to the repository root (including the hidden `.github` folder).
4. Open the repository's **Actions** tab.
5. Select **Build Nu-Link Android APK** and press **Run workflow** (or push to `main`).
6. When the run completes, open it and download the `NuLink-Android-debug-APK` artifact.
7. Extract the artifact ZIP; install `app-debug.apk` on an Android phone with USB OTG support.

No Android Studio or local Android SDK is required. The APK is a debug build and may show an Android installation warning because it is not distributed through Play Store.

Note: this APK is only a USB detection / HEX validation prototype. It does NOT yet erase, program, verify, or transfer offline data to the Nu-Link.
