# Performance (LIVE — download, TRIMMED)

Each time/ratio cell is **Text / Binary** (compressible `.txt` vs incompressible `.bin`).
Durations in **ms**. **vs** = AltaStata ÷ native **throughput** (higher is better).

- **Native …** = direct cloud SDK download (no AltaStata).
- **AltaStata (cloud)** = same file downloaded through AltaStata on that cloud.

## GCP

| Size | Native GCS ↓<br/>Text / Binary | AltaStata (GCP) ↓<br/>Text / Binary | vs<br/>Text / Binary |
|------|------|------|------|
| 1MB | 183 ms / 222 ms | 461 ms / 467 ms | 0.38x / 0.47x |
| 10MB | 495 ms / 520 ms | 635 ms / 1010 ms | 0.78x / 0.51x |
| 100MB | 4292 ms / 4320 ms | 1856 ms / 4396 ms | **2.31x** / 0.98x |
| 1GB | 43808 ms / 41392 ms | 11217 ms / 31410 ms | **3.90x** / **1.32x** |
| 5GB | 208495 ms / 243665 ms | 54601 ms / 177028 ms | **3.82x** / **1.38x** |

## Azure

| Size | Native Azure ↓<br/>Text / Binary | AltaStata (Azure) ↓<br/>Text / Binary | vs<br/>Text / Binary |
|------|------|------|------|
| 1MB | 148 ms / 150 ms | 224 ms / 343 ms | 0.64x / 0.43x |
| 10MB | 608 ms / 600 ms | 496 ms / 843 ms | **1.23x** / 0.71x |
| 100MB | 5109 ms / 5344 ms | 2296 ms / 5276 ms | **2.24x** / **1.02x** |
| 1GB | 52372 ms / 50554 ms | 11903 ms / 32646 ms | **4.40x** / **1.55x** |
| 5GB | 249424 ms / 321667 ms | 56151 ms / 156100 ms | **4.44x** / **2.06x** |

## AWS

| Size | Native S3 ↓<br/>Text / Binary | AltaStata (AWS) ↓<br/>Text / Binary | vs<br/>Text / Binary |
|------|------|------|------|
| 1MB | 137 ms / 157 ms | 291 ms / 304 ms | 0.45x / 0.51x |
| 10MB | 540 ms / 558 ms | 562 ms / 831 ms | 0.96x / 0.67x |
| 100MB | 6126 ms / 4616 ms | 3443 ms / 4968 ms | **1.77x** / 0.93x |
| 1GB | 49328 ms / 66368 ms | 12954 ms / 33564 ms | **3.81x** / **1.94x** |
| 5GB | 239970 ms / 318097 ms | 55801 ms / 155303 ms | **4.30x** / **2.05x** |

_Running: none_

- gcp: 10 download rows
- azure: 10 download rows
- aws: 10 download rows
