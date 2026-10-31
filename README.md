# Nu-Link Android — Milestone 7: Intel HEX validation (v0.7)

## Included in this build
- USB detection for Nu-Link clone VID `0416`, PID `511C`.
- Claims interface 1 and uses bulk IN `0x83` / OUT `0x04`.
- Capture-derived MS51PC0AE auto-detect diagnostic replay (435 request/response transactions).
- Full response comparison and copyable diagnostic report.
- Intel HEX file picker using Android Storage Access Framework.
- Intel HEX record validation: byte count, checksum, EOF, extended segment/linear address records, start-address records, and conflicting overlapping data.
- Explicit target selection for MS51FB9AE (16 KB APROM) and MS51PC0AE (32 KB APROM).
- Checks the highest HEX address against the selected APROM capacity.

## Important limitations
This is a diagnostic and HEX-validation milestone, **not yet a functional flash programmer**. The online-program and offline-transfer controls remain disabled. The supplied Windows Keil DLL confirms WinUSB usage and contains MS51-related code paths and flash-operation strings, but its USB programming command sequence has not yet been fully decoded or validated. The capture-based MCU detection exchange is not an independent silicon authentication method.

No APROM erase, write, verify, configuration-bit, or security/lock-bit command is sent by this build. Do not use it to program a target.

## Build APK with GitHub Actions
1. Extract this ZIP.
2. In the GitHub repository, upload the **contents** of this folder to the repository root, including the hidden `.github` directory.
3. Commit the changes. Version is bumped to `0.7` / versionCode `7`.
4. Open **Actions** and run **Build Nu-Link Android APK** (or push to `main`).
5. Download artifact `NuLink-Android-debug-APK` and extract `app-debug.apk`.

The current environment does not contain the Android SDK/Gradle installation needed to compile an APK locally. The included GitHub Actions workflow performs the APK build.
