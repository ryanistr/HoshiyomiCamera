# Stargazer Camera

External name: **Stargazer Camera** (short: **Stargazer**). Internal codename:
**HoshiyomiCam**. A from-scratch rebuild of the Transsion stock camera experience
(`com.transsion.camera` v12.0.01.001, reverse-engineered from `TCam/TCAM.apk`), written in
Kotlin + Jetpack Compose + Material 3, with every native compute library re-implemented in
Rust.

Reverse-engineering evidence and architecture analysis: [`docs/TCAM-ANALYSIS.md`](docs/TCAM-ANALYSIS.md).

## What this is

| Analyzed app (TCAM.apk) | Stargazer |
|---|---|
| View-based `BaseAppUI` (4,681 LOC) | Jetpack Compose + Material 3 |
| `IPlatformCamera2` — 307-method Camera2 contract with 486 vendor keys | `PlatformCamera` + `VendorKeyRegistry` (same tri-state UNKNOWN/EXIST/NOT_EXIST probing) |
| Reflective mode registry in resource string-arrays (191 features, 139 settings) | `ModeRegistry` with the same entry mechanism |
| 37 prebuilt `.so` (197 MB) | one Rust cdylib: `libhoshiomi_core.so` |

## Layout


app/                     Android app (Kotlin, Compose, M3, raw Camera2)
rust/hoshiyomi-core/     Rust crate — image/YUV/EXIF/barcode/bokeh, exposed over JNI
docs/TCAM-ANALYSIS.md    reverse-engineering report (evidence: jadx + aapt dumps)


## Rust core modules (replacing prebuilts)

9 modules, 66 unit tests, 36 JNI exports, ~490 KB — the whole native surface
of the app in one memory-safe library.

| Module | Replaces | Status |
|---|---|---|
| `yuv` | `libyuvutil.so` | rotate / mirror / scale / NV21→RGB(A), planar+semi-planar |
| `exif` | the app's 1,586-LOC `ExifInterface` | read/write JPEG EXIF |
| `barcode` | `libzbar.so` | QR decode |
| `bokeh` | `libstblur_preview_api.so` | portrait blur |

| `facedet` | `libcvface_api.so` | face detection + face-weighted metering |
| `imgproc` | `libArcFilter.so` / `libeffect.so` | filters, tone curves, vignette, sharpen |
| `beauty` | `libeffect.so` face-beauty / ByteDance BEF | frequency-separation skin smoothing + face enhancement

Beauty/AR vendor SDKs (FaceUnity Nama, ByteDance BEF, ArcSoft, Tencent Youtu, Unity/ARCore)
are licensed closed binaries; their Java-side feature contracts are preserved and filled in
behind open implementations.

## Build

Requires Android SDK (`local.properties` → `sdk.dir`), Rust toolchain with
`aarch64-linux-android` target, and NDK for the Rust linker.

bash
cp local.properties.example local.properties   # set sdk.dir
./gradlew assembleDebug                        # builds Rust core then the APK
cargo test --manifest-path rust/hoshiyomi-core/Cargo.toml

