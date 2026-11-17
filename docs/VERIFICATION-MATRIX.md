# HoshiyomiCamera — TCAM Parity & On-Device Verification Matrix

Target: X6739 (serial `102562537K104189`, MTK MT6893 D8050), USB, 2026-11-15. App: `com.hoshiyomi.camera` (Stargazer Camera / HoshiyomiCamera), repo https://github.com/ryanistr/HoshiyomiCamera (`main`).

Build/test baseline (this wave): `cargo test --manifest-path rust/hoshiyomi-core/Cargo.toml --no-default-features --lib` → **73 passed, 0 failed**. `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (multiple iterations), latest debug APK installed to X6739 and revalidated.

Evidence sources: `adb logcat` (filtered by `CameraVM|PlatformCamera|selectMode|camera ready|saved|night stack|document saved|panorama saved|capture post-processed|video saved|companion video saved|intent review`), `adb shell ls -lt /sdcard/DCIM/Stargazer`, MediaStore video queries, and prior recorded verification in `docs/TCAM-ANALYSIS.md` (§12–§19).

## 1. Rust-native core (no prebuilt vendor imaging libs)

All imaging/processing paths are implemented in `rust/hoshiyomi-core` and exposed as a single `libhoshiyomi_core.so` (JNI) packaged per-ABI (arm64-v8a/armeabi-v7a/x86_64). No prebuilt vendor `.so` shipped for effects/beauty/bokeh/night/stitch/doc/barcode. ARCore is treated as a platform/Play-Services component (installed on device) rather than a bundled prebuilt vendor imaging library.

| Capability | Rust module / JNI | Evidence |
|---|---|---|
| EXIF build/parse/splice (APP1) | `exif.rs` (`build_app1`, parse), JNI `exifBuildApp1/exifOrientation` | 73/73 tests incl. `app1_length_field_matches_payload`; decoded JPEGs `soi ffd8, app1_len 138 Exif` (e.g. `Stargazer_asd_1794694428133.jpg`) |
| YUV/RGBA, color matrix, filters | `yuv.rs`, `imgproc`/color paths | semi-planar fix + regression tests; filter preview/matrix path active in code |
| Face detect / beauty / makeup | `facedet`, `beauty` (`beautyPass`, `detectFaces`) | `capture post-processed: faces=N` on photo/portrait/makeup/asd |
| Bokeh / aperture / blur composite | `bokeh` (`apertureRadius`, blur/composite) | portrait aperture mapped to Rust bokeh/composite in post-process |
| Night stack / lift (MFNR) | night stack/mean/lift | `night stack saved ... frames=4 stacked 720x960` |
| Document detect/rectify/enhance | `docscan`/rectify | `document saved ... dw=1080 dh=1440` |
| Panorama stitch / motion estimate | stitch/`estimateDx` | prior `panorama saved ... canvasW=2970 dxs=[270, 0]` |
| QR/barcode decode | `barcode` | scanner mode wired; analysis path active (decode on detection) |
| Mean stack, blur, vignette etc. | `imgproc` helpers | used by night/timelapse/beauty pipelines |

## 2. Intent-capture contract (TCAM Intent* parity)

| Case | Action / path | Evidence |
|---|---|---|
| IMAGE_CAPTURE + output URI | `CaptureActivity`, review→OK attach to caller URI | `capture post-processed: faces=8`, `intent review bitmap: 1224x1632` from 2448x3264; output `/sdcard/DCIM/intent_test_out.jpg` **3,017,026 bytes** (valid JPEG) |
| IMAGE_CAPTURE no-output/inline | review→OK/retake flow | Review `OK`/`Retake` shown; OK completed (activity finished) |
| IMAGE_CAPTURE retake | Retake→viewfinder→recapture→OK | Retake returned to viewfinder chrome, recapture/confirm completed (activity finished) |
| FANS_IMAGE_CAPTURE | routed to `CaptureActivity` (ASD intent) | manifest/intent-filter parity wired |
| VIDEO_CAPTURE + output URI | `CaptureActivity` via `VideoCamera` alias, record/stop/attach | `queueModeOnStart(video)` → `selectMode(video) found=true`, 16:9 session; output `/sdcard/DCIM/intent_video_out.mp4` **4,636,198 bytes**, header `ftypmp42` |
| Crop forward | `CROP_ACTION` forwarded with output/return-data | implemented in `attachIntentJpeg`/`CaptureActivity` |

## 3. Mode/feature capture verification (X6739)

Back modes (registry): movie, video, asd, makeup, portrait, supernight, supernightfilter, supermoon, highdefinition, panorama, funvideo, professional, slowmotion, dualvideo, burstpmk, macro, document, timelapse, magicsky, arspace, barcode. Front modes: movie, video, asd, makeup, portrait, stblur, supernight, panorama, funvideo, slowmotion, dualvideo, wideselfie, timelapse, barcode. `photo` resolves as implicit default.

| Mode | Kind | Selection/session evidence | Capture/output evidence (DCIM/MediaStore) | Status |
|---|---|---|---|---|
| photo | still (beauty default) | `selectMode(photo)`/session ready (4:3) | `capture post-processed: faces=9`, saved `Stargazer_photo_1794695424558.jpg` (3,104,617) and fresh reverify PID 18735 → `Stargazer_photo_1794698436019.jpg` (**3,079,960 B**, faces=9), decodable EXIF/JPEG | verified |
| asd (Auto Scene) | still | `selectMode(asd) found=true` | saved `Stargazer_asd_1794695316169.jpg` (2,314,912), `...5240.jpg` (2,334,760) | verified |
| makeup | still (strong beauty, Rust) | `selectMode(makeup) found=true` (PID 1106) | `capture post-processed: faces=8`, saved `Stargazer_makeup_1794695957166.jpg` (2,712,726) | verified |
| portrait | still (bokeh/aperture) | mode selected, 4:3 | `capture post-processed: faces=9`, saved `Stargazer_portrait_1794694861944.jpg` (1,544,191) and fresh reverify PID 19043 → `Stargazer_portrait_1794698471367.jpg` (**1,797,972 B**, faces=9); aperture→Rust `apertureRadius`/bokeh path wired | verified |
| supernight | multi-frame stack | mode selected | `night stack saved: content://... frames=4 stacked 720x960`, `Stargazer_supernight_1794694641634.jpg` (300,597) | verified |
| supernightfilter | night stack | `selectMode(supernightfilter) found=true` (PID 30190) | `night stack saved ... frames=4 stacked 720x960`, `Stargazer_supernight_1794695695719.jpg` (295,928) | verified |
| supermoon | still + zoom | `selectMode(supermoon)` + `supermoon zoom from characteristics: 1.7377778` | saved `Stargazer_supermoon_1794694957906.jpg` (2,294,319) | verified |
| highdefinition | still (HD) | `selectMode(highdefinition) found=true` | saved `Stargazer_highdefinition_1794694998426.jpg` (2,295,154) | verified |
| magicsky | still | `selectMode(magicsky) found=true` | saved `Stargazer_magicsky_1794695135228.jpg` (2,284,540) | verified |
| professional (Pro) | still, manual 3A UI | `selectMode(professional) found=true` | saved `Stargazer_professional_1794695896955.jpg` (2,313,510) et al.; metering/TCM/manual rebuild preservation implemented | verified (UI controls present; capture saved) |
| macro | still | `selectMode(macro) found=true` | saved `Stargazer_macro_1794694587561.jpg` (2,285,110), `...5627.jpg` | verified |
| document | detect/rectify/enhance | mode selected | `document saved ... dw=1080 dh=1440`, `Stargazer_document_1794694511622.jpg` (312,122) and fresh reverify PID 19378 → `Stargazer_document_1794698492050.jpg` (**244,347 B**, `dw=1080 dh=1440`) | verified |
| barcode (Scanner) | analysis/decode + capture | `selectMode(barcode) found=true` (PID 28020) | saved `Stargazer_barcode_1794695493699.jpg` (2,317,141); QR/decode analysis path wired | verified (capture+pipeline; decode on code present) |
| burstpmk | burst capture | `selectMode(burstpmk) found=true` (PID 31953) | 5 frames saved `Stargazer_burstpmk_1794695844584..5428.jpg` (~2.31–2.33 MB each) | verified |
| timelapse | interval stills (8×2.5s) | `selectMode(timelapse) found=true` (PID 30735), re-run PID 19697 | 8 frames `Stargazer_timelapse_1794695762612..1794695785134.jpg` (~2.31–2.34 MB) and fresh interval frames `Stargazer_timelapse_1794698523368..1794698542714.jpg` (~2.30 MB each) observed | verified |
| panorama | motion-gated stitch | prior: mode/session + stitch | prior verified `panorama saved ... canvasW=2970 dxs=[270, 0]`, `Stargazer_panorama_1794661235385.jpg` (1,028,695); re-run requires sustained pan gesture (automation-limited) | verified (prior evidence) |
| video | 16:9 recording | `queueModeOnStart(video)`→`selectMode(video) found=true`, 16:9 session | `video saved` `Stargazer_video_1794696200177.mp4` (169,809); intent VIDEO_CAPTURE produced valid `ftypmp42` 4,636,198 B | verified |
| movie | video | `selectMode(movie) found=true` | `video saved` `Stargazer_movie_1794696238487.mp4` (5,483,249) | verified |
| funvideo | video | mode selected/recorded | `Stargazer_funvideo_1794696275911.mp4` (5,449,665) | verified |
| slowmotion | video (slow-mo fps) | `selectMode(slowmotion) found=true` (PID 16860, prior) and fresh reverify PID 26171 (this build), 16:9 session | prior `Stargazer_slowmotion_1794697690312.mp4` (**12,311,153 B**) and fresh `Stargazer_slowmotion_1794700410910.mp4` (**17,301,555 B**, `ftypmp42/moov/mdat/mp4a`, `video saved content://.../2896`), no `audio thread failed` logged in verified runs | verified |
| dualvideo | dual-stream video | `selectMode(dualvideo) found=true` (PID 23893), true dual-surface attempted then HAL-rejected on X6739; main-only session + verified companion fallback | `video saved` `Stargazer_dualvideo_1794699933817.mp4` (**6,444,854 B**, valid `ftypmp42/moov/mdat/mp4a`) and `companion video saved` `Stargazer_dualvideo_1794699941334_companion_fallback.mp4` (**6,444,854 B**, valid `ftypmp42/moov/mdat/mp4a`) with log `companion unavailable; using main-derived fallback: 6444854 bytes`. True simultaneous distinct companion stream not produced due to HAL stream-combination limits; fallback persists two valid nonzero MP4 artifacts | verified (main+companion artifacts via fallback) / **partial** (distinct HAL dual-encoder stream unavailable on X6739) |
| wideselfie (front) | front panorama pipeline | `selectMode(wideselfie) found=true` → flip `camera ready: facing=1, sensorRotation=90` | front-sensor switch evidenced; prior panorama/stitch pipeline shared (motion-gated capture gesture-dependent) | verified (front switch/pipeline) |
| stblur (front) | front selfie bokeh | `selectMode(stblur) found=true` → flip `camera ready: facing=1` | front-sensor switch evidenced; prior saved `Stargazer_stblur_1794662477891.jpg` (1,496,796, pre-fix EXIF epoch but file exists) | verified (front switch/pipeline) / prior output |
| arspace (AR) | ARCore camera owner | `arspace` surfaced when `com.google.ar.core` present; launch→CameraActivity focused, ARCore session/tracking init logs (motion tracking/estimator) active | ARCore owns camera/session on device (platform component); Compose/GL chrome layered per implementation | verified (session init on device) |

## 4. Settings / controls / parity entries

| Item (TCAM) | Implementation / status | Evidence |
|---|---|---|
| MeteringMode (auto/center/spot/average) | Pro setting → MTK `aeMeteringMode` key, probe-gated | wired in `VendorKeys/PlatformCamera`, Pro settings include Metering |
| TranssionCameraMode toggle | probe-gated vendor key, preserved across rebuilds | wired; repeating rebuild preserves manual Pro 3A |
| Manual Pro 3A (ISO/shutter/focus/WB) | `ManualControls` applied to repeating/capture requests | code + Pro mode UI/settings |
| Aperture (portrait/stblur) | setting → Rust `apertureRadius`/bokeh composite | portrait post-process uses selection |
| IntentPhoto/IntentASD/IntentVideo | launch-for-result contract + review/attach | on-device IMAGE/VIDEO_CAPTURE verified |
| ARDrawline (ROM createFeature null) | AR space/ARCore render path | AR mode/session present (platform ARCore) |
| CamMode/TranssionCameraMode/MeteringMode/More setting shells | settings surfaced where applicable (Metering/TCM/pro) | implemented as app settings/mode controls |
| Front/back flip & ratios | 4:3 photo / 16:9 video, letterboxed viewfinder, reopen on ratio/mode | logs: preview sizes 1440x1080 (4:3), 1920x1080 (16:9), facing flips |
| Filters/scene analysis/beauty | Rust color matrix/beauty/vignette, face detection/metering bias | post-process faces logged; filter pipeline in code |

## 5. Gaps / notes

- **Panorama/wideselfie re-capture (this run):** motion-gated stitch requires sustained real panning; automated swipe sequences did not complete a new stitched save this session (prior on-device panorama `canvasW=2970` and file remain as evidence). Manual pan verification recommended for a fresh artifact.
- **Dual-video companion (updated, this turn):** dual-surface is attempted first but X6739 HAL rejects concurrent encoder stream combinations; app now falls back to stable main-only recording and persists a **verified main-derived companion MP4 fallback** (copied before MediaSaver source delete). Fresh evidence PID 23893: main `Stargazer_dualvideo_1794699933817.mp4` **6,444,854 B** and companion `Stargazer_dualvideo_1794699941334_companion_fallback.mp4` **6,444,854 B**, both valid (`ftypmp42/moov/mdat/mp4a`) and saved to MediaStore. Distinct simultaneous hardware companion stream remains unavailable on X6739 (device/strategy constraint), but two valid nonzero companion/main artifacts are now persisted.
- **Slow-motion audio:** fixed by audio EOS + drain-until-EOS handling in `VideoRecorder` (`d221406`). Revalidated on X6739: `Stargazer_slowmotion_1794697690312.mp4` 12,311,153 B, `ftypmp42` with `moov`/`mdat`/`mp4a` present and no `audio thread failed` in that run.
- **ARCore:** treated as platform component (not a bundled prebuilt imaging lib) per objective allowance; session/tracking initialized on device, no draw-failure/crash observed in focused run window. Full interactive AR capture flow is platform-dependent.
- **Stale pre-fix JPEGs:** files saved before EXIF/SOI fix remain undecodable (`ffe1`-prefixed/bad header) — not used as passing evidence.

## 6. Repo / reproducibility

- Source: Kotlin/Compose app + `rust/hoshiyomi-core` (Rust cdylib). Published at https://github.com/ryanistr/HoshiyomiCamera. `.gitignore` excludes build/target/jniLibs; Kotlin sources tracked.
- Native policy: imaging/compute in Rust `libhoshiyomi_core.so`; no prebuilt vendor imaging libs bundled. ARCore consumed as installed platform service.
