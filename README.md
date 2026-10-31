# Nu-Link Android — Milestone 8: Protocol research & APROM manifest (v0.8)

## Included
- USB device discovery for Nu-Link clone VID `0416`, PID `511C`.
- Claims USB interface 1 and uses bulk IN `0x83` / OUT `0x04`.
- Retains the capture-derived MS51PC0AE detection diagnostic replay (435 request/response transactions).
- Full diagnostic response comparison and copyable report.
- Intel HEX file picker and strict record/checksum/EOF/address validation.
- Target selection for MS51FB9AE (16 KB APROM) and MS51PC0AE (32 KB APROM).
- Target-aware HEX capacity check.
- New **Build APROM Transfer Manifest** action. It creates a local report of populated address ranges and 32-byte data chunk counts for protocol analysis. It does not create USB commands or transmit firmware.
- Detection diagnostic is explicitly restricted to the MS51PC0AE capture reference. Selecting MS51FB9AE will not send the PC0AE detection sequence.

## Critical programming status
This is **not yet a working flash programmer**. APROM erase, write and verify remain disabled. The supplied Windows capture is a successful programming-session reference according to the user's confirmation, and the selected HEX file is the one used in that session. However, the capture's USBPcap framing, request/response pairing, command fields, target acknowledgements, erase semantics, and failure/retry behavior have not been independently decoded and validated sufficiently to safely transmit equivalent commands from Android.

Earlier exploratory packet labels such as `0xA0` and `0xA1` must be treated as provisional until the USBPcap records are correctly decoded. Do not infer command meanings from a byte value alone. Do not use this build to erase or program a target.

Configuration bits, security bits, and lock-bit operations are intentionally excluded.

## Build APK with GitHub Actions
1. Extract this ZIP.
2. Upload the **contents** of the extracted folder to the root of your existing GitHub repository. Include the hidden `.github` directory.
3. Commit the changes.
4. Open **Actions** and run **Build Nu-Link Android APK**, or push to `main`.
5. Download the `NuLink-Android-debug-APK` artifact and extract `app-debug.apk`.

The current environment does not contain the Android SDK/Gradle installation needed to compile an APK locally. The GitHub Actions workflow performs the Android build.
