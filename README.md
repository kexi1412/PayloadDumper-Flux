<div align="center">

# Payload Dumper · Flux

**English** · [简体中文](<README.zh-CN.md>)

Extract partition images on Android — from a **picked local file** or an HTTP OTA direct link.

![Version](https://img.shields.io/badge/Version-3.0.0-0080FF)
![Platform](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/UI-COUI17%20%2F%20Compose-0080FF)
![Build](https://github.com/kexi1412/PayloadDumper-Flux/actions/workflows/build.yml/badge.svg)

</div>

## New in 3.0.0

- **Picker-only local source:** tap the file card to open Android's system document picker. Local mode contains no path text field, cannot open the keyboard, and shows the selected name and size. Cancelling the picker keeps the previous selection.
- **Independent sources:** the local document and network URL are remembered separately. Changing modes cannot accidentally parse the other source; an active task locks source changes.
- **Firmware information:** Android version, security patch, kernel version, build fingerprint, file name, total file size, partition count and block size. Tap an information card to view/copy its full value.
- **Resource-based COUI17 design:** light/dark blue palette, independent rounded cards, assignment-style information rows, 32→24sp collapsing title, segmented selector, accessible actions and scrollable About/settings dialogs.
- **Correctness fixes:** exact HTTP Range validation; RFC 5987/URL filename decoding; lazy partition rows; atomic progress updates; nested-archive output isolation; bounded metadata parsing; source/relink cancellation guards; manifest-bound resume journals; per-range gzip/deflate inflation for CDNs that ignore `Accept-Encoding: identity`.
- **Version consistency:** `versionName=3.0.0`, `versionCode=30`; About and the default User-Agent follow the build version.

See [CHANGELOG](<CHANGELOG.md>) and the [COUI17 reference notes](<docs/COUI17_REFERENCE.md>).

## Firmware information: sources and limits

Information describes **the selected firmware**, not the phone running this app. Missing or unsupported values are explicitly shown as unavailable.

| Field | Source |
|---|---|
| File name / total size | SAF document metadata and descriptor length, or the final HTTP URL / Content-Disposition and Content-Range total |
| Android version | Target `post-build` fingerprint / protobuf postcondition, target `build.prop`, or a known target SDK mapping; optional boot header fallback |
| Security patch | Target OTA metadata, target `build.prop`, payload manifest, or boot header **month precision** fallback |
| Build fingerprint | Target OTA metadata or target `build.prop`; never the pre-update build or a partition hash |
| Kernel version | `Linux version` banner in the target `boot.img` kernel region, including supported gzip/LZ4 kernel encodings |

The reader supports `META-INF/com/android/metadata.pb` and text `metadata` in STORED or DEFLATE ZIP entries, with range, exact length and CRC checks. It reads explicitly named `build.prop` ZIP entries; it does **not** download/mount an entire system/vendor image to discover properties.

Kernel inspection reconstructs the needed boot prefix from full-payload operations and maps every destination extent. It does not use the reference project's fixed percentage sample. Metadata is limited to 1 MiB per entry; boot inspection to 64 MiB, 16 MiB per operation and a 64 MiB read budget. XZ inspection also has a decoder memory limit. Unsupported/delta boot data, absent metadata or an exceeded budget leaves fields unavailable without blocking normal extraction. A bare `payload.bin` often cannot supply an Android version or fingerprint.

## Extraction support

- **Full A/B OTA:** raw `payload.bin`, or an OTA ZIP with a STORED `payload.bin`.
- **Firmware ZIP images, Huawei UPDATE.APP and Honor/Huawei nested SD update packages:** image enumeration, per-image extraction, and a single streaming pass for images sharing an inner ZIP. Block-based OTA containers expose their data/list files; they are not automatically converted to reconstructed filesystem images.
- **Parallel full-payload operations:** positioned reads and writes with bounded worker concurrency; `REPLACE`, `REPLACE_BZ`, `REPLACE_XZ`, `ZERO` and `DISCARD`. Known delta operations require old partition data and are not supported.
- **SHA-256 verification:** operation hashes when present, plus the optional final image hash. Invalid ranges or decoded lengths are rejected rather than marked complete.
- **Resume:** completed operations are journaled with the exact manifest, partition and block-size identity. Unbound legacy journals, a different manifest, or a missing/truncated output image cannot skip writes. A replacement URL must serve an identical full manifest/operation layout before existing progress is reused.
- Search/filter, per-image status, custom User-Agent, and English/Simplified Chinese localization.

Output defaults to `Download/PayloadDumper/<firmware>/`. Nested archives use separate subdirectories to avoid collisions such as two different `preload.img` entries. Archive/provider names are treated as leaf names and checked against the chosen output directory.

## COUI17 implementation

The palette, component dimensions and selected text-style inheritance were checked against the user-supplied **Settings 17.0.0** sample. Examples: `#0080FF` / `#1A8CFF` accent, 12dp COUI preference card radius, 16sp list titles and 13sp assignment values. The 20dp SettingsLib token is a separate `v36` resource and is not labelled as a COUI outer-card size.

This is a Compose implementation using the project's own icons. OEM fonts, assets and runtime libraries are not bundled. Circular corners, line heights, press animations and 48dp interaction targets are documented adaptations; static resource analysis alone is not a claim of pixel-identical runtime blur or spring motion. Details, resource IDs and sample hashes are in [the reference report](<docs/COUI17_REFERENCE.md>).

## Build and test

Requires JDK 21, Android SDK Platform 35 / Build-Tools 35, Gradle 8.10.2, AGP 8.8 and Kotlin 2.1.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
# unsigned release: app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew connectedDebugAndroidTest  # requires a connected Android device/emulator
```

On Windows, use `gradlew.bat`. Put the checkout/SDK under an ASCII path (a directory junction is sufficient), and **quote dotted Gradle arguments** in PowerShell:

```powershell
& .\gradlew.bat '-Pandroid.overridePathCheck=true' testDebugUnitTest assembleRelease
```

The repository's [GitHub Actions build](<.github/workflows/build.yml>) runs tests/lint, builds and signs an APK, and publishes it as an artifact and Release. Signing configuration is documented in the workflow; a build using a different signing certificate cannot replace an older installed APK with the same application ID.

The regression suite covers target-versus-pre-update metadata, protobuf-only packages, unknown fields, CRC/truncation/size failures, boot headers and gzip/LZ4 kernels, multi-extent boot reconstruction, source separation, encoded filenames, strict Range responses, and resume/extraction boundary cases. Compose instrumentation tests assert that local mode has **zero text-edit actions**; network mode alone has an editor.

## Permissions

Reading a picked local document uses its SAF URI grant and does **not** need All files access. Extraction to the chosen filesystem/download folder still requires `MANAGE_EXTERNAL_STORAGE`; the app opens the relevant settings when needed. Network direct links must provide correct HTTP Range responses. No GitHub token, Xiaomi account or updater API credential is required by the app.

## Architecture

```text
core/       Positioned sources, ZIP/container parsing, metadata/boot inspection,
            bounded full-payload extraction, verification and identity-bound resume
model/      Shared firmware, partition and independent source-selection models
viewmodel/  StateFlow orchestration, task locking and generation-checked relinking
ui/         Resource-based COUI17 Compose theme, file picker and information cards
```

## Credits

- **[YuKongA (yukong) / Updater-KMP](<https://github.com/YuKongA/Updater-KMP>)** — thank you for the OTA metadata and kernel-inspection ideas and firmware-information presentation. Reference revision: `73db06dc98d04fe33deebbc6e34b260ed8269aea`. Flux implements its reader independently; it does not use the Xiaomi-only API, ODM-priority policy or percentage-based boot sampling.
- **xyiguanle** — payload analysis source.
- **[rcmiku/Payload-Dumper-Compose](<https://github.com/rcmiku/Payload-Dumper-Compose>)** — predecessor and feature-flow reference.
- **payload-dumper-c** — positioned-source and parallel/scattered-write engine design reference.
- Jetpack Compose, OkHttp, Apache Commons Compress, tukaani-xz, aircompressor and protobuf.

## License

Apache-2.0 — see [LICENSE](<LICENSE>). The update-metadata protobuf schema comes from AOSP (Apache-2.0). Updater-KMP is also Apache-2.0; no upstream source or OEM binary assets were copied into this refactor.
