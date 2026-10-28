# Changelog

## 3.0.2 — 2026-10-29

### Fixed / 修复
- `Content-Encoding` is now parsed as an encoding list, and only real `gzip`/`x-gzip`/`deflate` members trigger decompression. A server that mislabels the header as `utf-8` (or any other non-compression token) no longer fails the parse; its bytes are treated as raw and length-checked.
- Regression tests cover a charset mislabel and a list that still contains gzip.

## 3.0.1 — 2026-10-29

### Fixed / 修复
- Network links whose CDN ignores `Accept-Encoding: identity` and gzip/deflate-compresses each `Range` response no longer fail at parse with “Encoded HTTP range body…”. Each range is inflated independently and must decode to exactly the requested byte count; over- or under-run is rejected instead of mixing bytes.
- Requests now send `Cache-Control: no-transform` to discourage intermediary re-compression.
- Mislabeled `Content-Encoding: gzip` responses whose body is actually identity are accepted only when the body length proves it.
- Unsupported encodings (for example `br`) now report a precise protocol error naming the encoding.

## 3.0.0 — 2026-10-29

### Added / 新增
- Picker-only local document card; local and network sources are persisted independently.
- Firmware Android version, security patch, kernel version, build fingerprint, file name and total size; full-value view/copy.
- Bounded text/protobuf OTA metadata and boot inspection, with STORED/DEFLATE, gzip/LZ4 and multi-extent support.
- Resource-verified COUI17 light/dark tokens, independent rounded cards, typography, segmented actions and scrollable dialogs.
- About/README feature list and thanks to [YuKongA (yukong) / Updater-KMP](<https://github.com/YuKongA/Updater-KMP>).

### Fixed / 修复
- Local path input replaced with a real system file-picker action.
- Wrong-mode input reuse, stale source state, double relink submission and cancellation after source replacement.
- Resume journals now bind the whole manifest, partition, block size and output length; mismatched/legacy records cannot skip writes.
- Batch/single extraction contention, atomic progress updates and nested same-name image collisions.
- HTTP Range ignored/mismatched responses, encoded filenames, output leaf names and partial raw writes.
- Metadata CRC/truncation/size checks; extraction plan/blob/extent validation and exact decoded lengths.
- Lazy partition list and long metadata display; unavailable fields are not replaced with host-device information.

### Version / 版本
- `versionName`: 2.2 → 3.0.0; `versionCode`: 22 → 30.
- About and default User-Agent use the build version.

### Notes / 说明
- Full OTA extraction remains supported; source-dependent delta operations require old images and are not supported.
- Firmware information is best-effort and target-derived. Missing or over-budget fields remain unavailable.
- COUI17 is a resource-based Compose adaptation, not a claim of runtime blur/spring pixel equivalence.
