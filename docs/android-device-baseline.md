# Android Physical-device Verification Baseline

> Date: 2026-07-12
> Result: one-device Android fixture passed; this is not a broad device or production validation

The current instrumentation compilation was reverified on 2026-07-26. The physical-device fixture
has not been rerun for the current tree.

This baseline records the reproducible physical-device evidence produced by the isolated
published-artifact consumer. It validates Android platform adapters and selected failure/performance
boundaries without using a live Provider credential or modifying a host application's data.

## Device and command

| Property | Value |
|---|---|
| Device | Samsung SM-S9180 |
| OS | Android 16 / API 36 |
| ABI | arm64-v8a |
| Security patch | 2026-06-05 |
| Build privilege | non-root user build |

Run with exactly one authorized device, or select one explicitly:

```bash
MAGRATHEA_ANDROID_SERIAL=YOUR_ADB_SERIAL \
  ./gradlew verifyAndroidDevice --warning-mode all --console=plain --no-daemon
```

The task publishes the current SDK to the isolated verification repository, builds a separate Android
application and instrumentation APK, refuses to replace either
test package if it already exists, installs them for the current Android user, and removes both packages
and the `adb reverse` rule on success or failure. Cleartext loopback traffic is enabled only by the
consumer's debug manifest. This external task is intentionally not attached to `verifySdkRelease`.

## Verified scenarios

| Stage | Physical-device evidence |
|---|---|
| Credential write | A non-exportable AES-256 Android Keystore key supported encrypt/decrypt with GCM and no padding. `KeyInfo.securityLevel=1` is Trusted Environment on this API. The 303-byte ciphertext envelope was under `noBackupFilesDir`; plaintext, credential identity, endpoint, and header canaries were absent. Overwrite and repeated remove behaved deterministically. |
| Room write/restart/read | Two current-schema sessions and checkpoints were written to a real 36,864-byte Room database, the process exited, the package was force-stopped, and a new instrumentation process restored both. Credential read was 52 ms and Room read was 74 ms in the final run. Repeated delete/clear was idempotent; a deliberately corrupted row failed closed and produced two sanitized reports. |
| Android HTTP | The default Android Ktor/OkHttp engine completed loopback GET, two-event SSE, and sanitized HTTP 500 handling. A full `GeminiProviderAdapter` request used the fake key only in `x-goog-api-key`, kept it out of the JSON body, matched the current stable-v1 request shape, and decoded five canonical events with exactly one final `Completed` event and expected usage. No external provider was contacted. |
| Cancellation | After the client received an SSE event, coroutine cancellation joined in 3 ms. A device-local IPv4 TCP server then observed its socket close through a failed write, proving transport cancellation below the Flow boundary without relying on `adb reverse` buffering. |
| 1,000-message sample | Real Room save took 137 ms and load took 495 ms. Application PSS was 72,700 KiB with a 6,722 KiB scenario delta; Java heap use was 21,470 KiB. Concurrent `dumpsys meminfo` reported total PSS 72,564 KiB and total RSS 157,208 KiB. These are one-run observations, not release thresholds. |

Detailed generated reports are written to `build/reports/android-device/`. `simpleperf` hardware
counters were unavailable to this non-root package invocation, so no Simpleperf result is claimed.

## Evidence boundary

This run closes the selected Android physical-device sub-gate for this model/API combination. It does
not prove:

- cold reboot, OS-initiated process death, device-to-device transfer, or an actual Auto Backup restore;
- Wi-Fi/cellular transitions, airplane mode, long Doze/background execution, or hostile network paths;
- a live Gemini request with a real credential, any non-Gemini live provider, or a complete product UI;
- Android API 24/OEM breadth, statistically controlled performance, rooted hardware counters, or a
  production security sign-off;
- iOS physical-device Keychain/ARC/Instruments behavior or any other external release evidence.
