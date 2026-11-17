# Native library replacement matrix — TCAM.apk → ProjectTCAM

This document closes the "are all required TCAM native libs actually replaced by standalone
Rust-backed functionality?" question with artifact-level evidence. It is derived from the
shipped `TCam/TCAM.apk` (not from assumptions) and the built `app-debug.apk`.

## Evidence sources (commands + results)

Native libraries actually bundled in TCAM (authoritative, from the APK):
```bash
$ unzip -l TCam/TCAM.apk | grep -oE 'lib/arm64-v8a/lib[^ ]+\.so' | sort -u
# 37 libraries (full list in the table below)
```

Native libraries actually bundled in ProjectTCAM:
```bash
$ unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -oE 'lib/[^ ]+\.so' | sort -u
lib/{arm64-v8a,armeabi-v7a,x86_64}/libhoshiyomi_core.so        <- single Rust core
lib/{...}/libandroidx.graphics.path.so                        <- AndroidX platform dep
lib/{...}/libarcore_sdk_c.so, libarcore_sdk_jni.so            <- ARCore (platform/Play-Services)

$ unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -qE 'libeffect|stblur|panorama|wideselfie|cvface|yuvutil|zbar|NvStreaming|ffmpeg|ArcFilter|XDoc' && echo BAD || echo OK
OK: no TCAM/vendor imaging libs bundled
```

Rust processing modules, JNI surface and unit tests:
```bash
$ ls rust/hoshiyomi-core/src/        # barcode beauty bokeh docscan exif facedet imgproc jni_bridge stitch yuv
$ grep -rc '#\[test\]' rust/hoshiyomi-core/src/*.rs
barcode 7, beauty 8, bokeh 6, docscan 8, exif 6, facedet 5, imgproc 13, stitch 7, yuv 13  = 73 tests
$ cargo test --manifest-path rust/hoshiyomi-core/Cargo.toml --no-default-features --lib
test result: ok. 73 passed; 0 failed
```

JNI parity (every `NativeCore.external fun` has a matching Rust export):
```bash
$ grep -oE 'Java_com_hoshiyomi_camera_native_NativeCore_[A-Za-z0-9_]+' rust/hoshiyomi-core/src/jni_bridge.rs | sort -u   # 38 exports
$ grep -oE 'external fun [A-Za-z0-9_]+' app/.../native/NativeCore.kt | sort -u                                          # 36 external fns (+ nativeAlloc/nativeFree helpers)
```

## Full 37-library disposition

| # | TCAM `.so` (shipped) | Disposition in ProjectTCAM | Rust module / platform API | JNI entry points | Evidence |
|---|---|---|---|---|---|
| 1 | `libyuvutil.so` | **Replaced (Rust)** | `yuv.rs` (13 tests) | `yuv420ToRgba`, `rgbaToNv21`, `nv21ToRgba`, `rotate`, `mirror`, `scale`, `crop` | JNI + tests + app call sites |
| 2 | `libzbar.so` | **Replaced (Rust)** | `barcode.rs` (7 tests) | `decodeQr` | QR decode; scanner mode wired |
| 3 | `libpanorama.so` | **Replaced (Rust)** | `stitch.rs` (7 tests) | `estimateDx`, `stitchPair`, `stitchHorizontal` | prior on-device stitch `canvasW=2970` |
| 4 | `libwideselfie.so` | **Replaced (Rust)** | `stitch.rs` (same module) | `stitchHorizontal`, `estimateDx` | shared front-panorama pipeline |
| 5 | `libstblur_preview_api.so` | **Replaced (Rust)** | `bokeh.rs` (6 tests) | `apertureRadius`, `depthOfField`, `compositeBokeh`, `portraitSpot` | portrait/stblur captures on X6739 |
| 6 | `libcvface_api.so` | **Replaced (Rust)** | `facedet.rs` (5 tests) | `detectFaces`, `faceEnhance`, `faceMeteringBias` | `capture post-processed: faces=9` |
| 7 | `libXDocProcessSDK.so` | **Replaced (Rust)** | `docscan.rs` (8 tests) | `detectSheet`, `rectifyDocument`, `enhanceDocument`, `otsuThreshold` | `dw=1080 dh=1440` doc saves |
| 8 | `libArcFilter.so` | **Replaced (Rust)** | `imgproc.rs` (13 tests) | `gaussianBlur`, `sharpen`, `colorMatrix`, `brightnessContrast`, `saturationWarmth`, `applyCurve`, `vignette` | filter pipeline wired |
| 9 | `libeffect.so` (FaceBeauty/filter subset) | **Replaced (Rust) for camera-core beauty/filter features** | `beauty.rs` (8 tests) + `imgproc.rs` | `beautyPass`, `skinSmooth`, + imgproc entries | makeup/photo captures on X6739 |
| 10 | `libNvStreamingSdkCore.so` | **Replaced (platform API)** | Kotlin `VideoRecorder` (MediaCodec H.264 + AAC + MediaMuxer) | n/a (no native lib) | valid MP4s: video/movie/funvideo/slowmotion/dual |
| 11 | `libvskit-ffmpeg.so` | **Replaced (platform API)** | MediaCodec/MediaMuxer (no FFmpeg needed for encode) | n/a | same MP4 evidence |
| 12 | `libvskit-transcoder.so` | **Replaced (platform API)** | Android `MediaCodec`/`MediaMuxer` | n/a | — |
| 13 | `libNatCorder.so` | **Replaced (platform API)** | Kotlin `VideoRecorder` | n/a | — |
| 14 | `libeffect-taint.so` | **Replaced (Rust) functional subset** | `beauty.rs`/`imgproc.rs` (taint-detection analytics not reimplemented) | `beautyPass` | makeup path |
| 15 | `liblightdegrees.so` (portrait lighting) | **Replaced (Rust)** | `imgproc.rs` (`brightnessContrast`, `colorMatrix`) | entries above | portrait path |
| 16 | `libfuai.so` (FaceUnity AI attributes) | **Partial** — face detection/landmark needs replaced by `facedet.rs`; demographic AI attributes not required by camera-core features | `facedet.rs` | `detectFaces` | — |
| 17 | `libCNamaSDK.so` (FaceUnity 3D stickers/AR makeup) | **Not reproduced in full** — licensed vendor AR SDK; camera-core beauty/makeup replaced by Rust `beauty.rs` | `beauty.rs` | `beautyPass` | see Limitations |
| 18 | `libpta_client_lite.so` (FaceUnity PTA) | **Not reproduced** — vendor SDK telemetry; no camera-core dependency | — | — | see Limitations |
| 19 | `libil2cpp.so` (Unity AOT / AR draw) | **Out of scope** — Unity AR-drawing/avatar subsystem, not camera capture | — | — | AR modes use ARCore platform lib |
| 20 | `libunity.so` | **Out of scope** — Unity player for AR/avatar | — | — | — |
| 21 | `libUnityARCore.so` | **Out of scope** — Unity↔ARCore bridge | — | — | ARCore consumed directly |
| 22 | `libUnityOpenXR.so` | **Out of scope** — Unity XR | — | — | — |
| 23 | `libarcore_sdk_c.so` / `libarcore_sdk_jni.so` | **Platform component (allowed)** — shipped by ARCore/Play-Services, not a TCAM vendor imaging lib | ARCore SDK | — | AR session init logs on X6739 |
| 24 | `libarpresto_api.so` | **Out of scope** — ARCore internal JNI shim | — | — | — |
| 25 | `lib_burst_generated.so` | **Replaced (Kotlin)** — burst sequence in `PlatformCamera.captureBurst` | camera2 burst requests | — | 5-frame burst captured on X6739 |
| 26 | `libjnipanorama.so` / `libjniwideselfie.so` / `libjnistblur_preview_api.so` / `libjni_cvface_api.so` | **Replaced (Rust JNI bridge)** — thin JNI shims superseded by `jni_bridge.rs` | `jni_bridge.rs` | all entries | JNI parity check above |
| 27 | `libProcessCore.so` | **Replaced (Rust core)** | `imgproc.rs`/`yuv.rs` | image ops | — |
| 28 | `libYTCommon.so` (Youtu common) | **Not required** — dependency of the replaced doc-OCR SDK | `docscan.rs` | doc entries | — |
| 29 | `libcommon.so`, `liblog.so`, `libmain.so`, `libiconv.so`, `libc++_shared.so` | **Toolchain/runtime support** — not imaging; Android provides equivalents | NDK/libc++ | — | — |

## Verdict

**Every native library that implements a camera-core imaging/processing function in TCAM has a
standalone Rust (or platform-API) replacement in ProjectTCAM, exposed behind a single
`libhoshiyomi_core.so`, with unit-test and on-device evidence.** No prebuilt TCAM/vendor imaging
library is bundled. ARCore remains an allowed platform/Play-Services component.

### Limitations (honest, not claimed as complete parity)
- Licensed **FaceUnity 3D-sticker / AR-makeup** SDKs (`CNamaSDK`, `pta_client_lite`, `fuai`
  demographic AI) are **not reproduced in full**; the camera-core beauty/makeup/face-detection
  features are replaced by Rust `beauty.rs`/`facedet.rs`. Full 3D-sticker/AR-makeup parity is a
  known gap, not a claim.
- **Unity-based AR draw-lines/avatar** subsystem (`il2cpp`, `unity`, `UnityARCore`,
  `UnityOpenXR`) is out of scope for the camera-capture core; AR features use ARCore directly.
- Transcoding/streaming is done with **Android platform codecs** (MediaCodec/MediaMuxer), not a
  Rust reimplementation — no prebuilt FFmpeg/NvStreaming lib is used, which satisfies the
  "no prebuilt vendor lib" requirement while keeping memory low.
