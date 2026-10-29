# StepAOS parse-only fixture

Source: <https://ota-firmware.stepaos.com/Firmware/StepAOS/pandora/1.0.0.2/cn/20261004_131555/6718923088/update.zip>

Captured 2026-10-29 with validated HTTP 206 / exact Content-Range / identity encoding.
The source is **6,718,923,088 bytes**, ETag `c93b8274210561d64d03dfb190f45518-801`.

| File | Absolute source offset | Length | SHA-256 |
|---|---:|---:|---|
| parse-head.bin | 0 | 259101 | ff3b5c8870f3ba95c120da698b4eefdda9331bbf7d76c60ae9b2e236061db092 |
| zip-tail.bin | 6718857511 | 65577 | b015b75a2f0a655116ece3b39d5577593ca804200aa4619aba5948a064fe39ea |

Seven ZIP entries, ZIP64, STORED payload at offset 4910; CrAU v2 manifest is 253900 bytes,
metadata signature 267 bytes, first operation at 259101; 57 partitions, 4096-byte blocks.
The fixture deliberately contains **no boot-operation data**. The sparse source rejects and
counts any attempted operation read, even if an optional-metadata catch would swallow it.
Only parse metadata and the ZIP tail are included, not full partition images.
