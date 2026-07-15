<div align="center">

# Payload Dumper · Flux

**English** · [简体中文](README.zh-CN.md)

Extract partition images from **full** Android A/B (`payload.bin`) OTAs — in parallel, on-device, from a local file **or a download link**.

![Platform](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)
![Build](https://img.shields.io/badge/Build-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white)

</div>

---

**Payload Dumper · Flux** pulls individual partition images (`boot.img`, `system.img`, `vendor.img`, …) straight out of a full A/B OTA package. Point it at a local `payload.bin` / OTA zip, or paste an OTA **direct link** — it parses the archive over HTTP Range requests and never has to download the whole package first.

The extraction engine is a from-scratch rewrite built around a **stateless positioned-read source** and a real thread pool, closely following the design of `payload-dumper-c`. The interface is an in-house take on the **ColorOS 16 “Flux”** glassmorphism design language, built with Jetpack Compose + Material 3.

## Highlights

- **Two sources** — a local `payload.bin` / OTA zip, or a remote OTA **direct link** (streamed via HTTP Range; no need to fetch the full package up front).
- **True parallel extraction** — every install operation is an independent task that reads by absolute offset and writes by absolute position, up to `CPU-core-count` in flight. No global lock, no single shared cursor.
- **SHA-256 verification** — each extracted image is re-hashed and compared against the manifest, with a ✓ / ✗ badge in the UI.
- **Resumable network downloads** — big partitions are slow and OTA links expire. A link dying mid-download **won't lose progress**: every completed operation is recorded in a `<partition>.img.progress` sidecar (survives app restarts), and a retry only fetches what's missing.
- **Forced relink + re-verification** — when a network read stalls, a mandatory dialog asks for a fresh link. On submit, the partition's manifest SHA-256 is checked **first** to confirm it's the same ROM before resuming — a mismatched link can't silently corrupt a half-written image. A final whole-image SHA-256 check still runs at the end.
- **Batch “extract all”**, partition search/filter, per-tile progress rings and status.
- **Custom User-Agent** (optional).
- **Light / dark themes**, adaptive icon, and full **English / Simplified Chinese** localization.

## Supported operation types

The four used by standard full OTAs: `REPLACE`, `REPLACE_BZ` (bzip2), `REPLACE_XZ` (xz/lzma), and `ZERO` / `DISCARD`. Decompressed data is scattered across **all** of a partition's `dst_extents` (not just the first), matching `payload-dumper-c` behavior.

For **unrecognized** operation types, the engine sniffs the block's magic bytes (zstd `28 B5 2F FD` / xz / bzip2) and picks a decoder automatically — so even a non-standard OEM zstd block works without guessing enum numbers.

> Incremental (delta) OTAs are **not** supported (`SOURCE_COPY` / `SOURCE_BSDIFF` / `BROTLI_BSDIFF`, …); the app reports this clearly. Use a full package.

## Architecture

```
core/
  PayloadSource.kt    Stateless positioned-read interface + FileSource (FileChannel) + HttpSource (one Range request per read)
  ZipLocator.kt       Locates payload.bin inside an OTA zip central directory (ZIP64-aware); same path for file & URL
  PayloadParser.kt    Parses the CrAU header (big-endian) + protobuf manifest
  Decompressor.kt     xz / bzip2 / zstd (+ magic-byte sniffing fallback)
  PayloadExtractor.kt Parallel engine: semaphore throttling, positioned writes, scattered extents, SHA-256 verify
  Net.kt              OkHttp client (injects custom UA / headers)
model/      Data models shared between UI and engine
viewmodel/  DumperViewModel: parse / extract / state orchestration (StateFlow)
ui/         ColorOS 16 Flux theme + components + main screen
```

**Key design:** every read through `PayloadSource` is a **stateless positioned read** (`readAt(offset, len)`), mirroring `payload-dumper-c`'s `source_interface.h` (clone / read-at-offset / destroy). That's what lets multiple workers extract concurrently over both file and network sources.

## Localization

The entire UI — screens, dialogs, and status/error messages — is driven by string resources, so it follows the **current system language** (or the per-app language on Android 13+, wired via `generateLocaleConfig`). **English** (default) and **Simplified Chinese** (`zh-rCN`) ship in the app. To add a language, drop a `res/values-<locale>/strings.xml` alongside the existing ones — no code changes required.

## Build (GitHub Actions — recommended)

The repo ships a `.github/workflows/build.yml` that, on `ubuntu-latest` (x86_64), installs JDK 21 + Android SDK, runs `assembleRelease`, signs with a keystore generated inside the workflow, and produces an installable APK.

- Push to `main` or trigger manually (**Actions → Build APK → Run workflow**).
- Output: the run's **Artifact** (`PayloadDumper-Flux-apk`), plus an auto-published **Release**.
- Zero secrets. Note: each build generates a **fresh signing key**, so uninstall the old version before installing over it.

## Build locally

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
# zipalign + apksigner it yourself before installing
```

Requires: JDK 21, Android SDK Platform 35 / Build-Tools 35, AGP 8.8, Kotlin 2.1.

## Permissions

Needs **All files access** (`MANAGE_EXTERNAL_STORAGE`) to read local packages from any path and write `.img` files to the download folder. The app walks you through granting it the first time it's needed.

## Credits

- **xyiguanle** — payload analysis source; the engine's parsing logic is heavily inspired by it.
- **[rcmiku/Payload-Dumper-Compose](https://github.com/rcmiku/Payload-Dumper-Compose)** — the predecessor project; feature and flow reference.
- Engine reference: `payload-dumper-c` (thread pool / stateless source / scattered-extent writes).
- Dependencies: Jetpack Compose, OkHttp, Apache Commons Compress, tukaani-xz, aircompressor, protobuf.

The UI layout is designed for this project; the palette follows the in-house ColorOS 16 “Flux” theme.

## License

Apache-2.0 — see [LICENSE](LICENSE). `app/src/main/proto/update_metadata.proto` is from AOSP (Apache-2.0).
