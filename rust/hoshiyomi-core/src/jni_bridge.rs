//! JNI bridge — exposes the Rust core to the Kotlin app.
//!
//! Entry points use the `Java_com_hoshiyomi_camera_native_NativeCore_*` naming
//! so the Kotlin side's `external fun` declarations line up exactly. Every
//! entry converts its Java buffers into owned Rust slices, runs the pure-Rust
//! algorithm, and writes results back — one copy per direction, no persistent
//! allocations across calls.

use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

/// Copy a jbyteArray into an owned Vec<u8>.
fn bytes(env: &JNIEnv, arr: &JByteArray) -> Result<Vec<u8>, jni::errors::Error> {
    env.convert_byte_array(arr)
}

/// Write u8 data back into a jbyteArray.
fn put_bytes(env: &JNIEnv, arr: &JByteArray, buf: &[u8]) -> Result<(), jni::errors::Error> {
    let signed: Vec<i8> = buf.iter().map(|&b| b as i8).collect();
    env.set_byte_array_region(arr, 0, &signed)
}

fn report(env: &mut JNIEnv, e: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", e);
}

macro_rules! run {
    ($env:expr, $body:expr) => {
        match $body {
            Ok(v) => v,
            Err(err) => {
                report(&mut $env, &format!("{:?}", err));
                return 0 as _;
            }
        }
    };
}

// ---------------------------------------------------------------------------
// yuv
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_yuv420ToRgba(
    mut env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    y: JByteArray,
    y_stride: jint,
    u: JByteArray,
    u_stride: jint,
    u_px: jint,
    v: JByteArray,
    v_stride: jint,
    v_px: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let yb = bytes(&env, &y)?;
        let ub = bytes(&env, &u)?;
        let vb = bytes(&env, &v)?;
        let mut ob = vec![0u8; (width as usize) * (height as usize) * 4];
        crate::yuv::yuv420_to_rgba(
            width as u32, height as u32,
            &yb, y_stride as u32,
            &ub, u_stride as u32, u_px as u32,
            &vb, v_stride as u32, v_px as u32,
            &mut ob,
        )?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_nv21ToRgba(
    mut env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    src: JByteArray,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &src)?;
        let mut ob = vec![0u8; (width as usize) * (height as usize) * 4];
        crate::yuv::nv21_to_rgba(width as u32, height as u32, &sb, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_rgbaToNv21(
    mut env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    src: JByteArray,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &src)?;
        let mut ob = vec![0u8; (width as usize) * (height as usize) * 3 / 2];
        crate::yuv::rgba_to_nv21(width as u32, height as u32, &sb, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

/// Returns packed (width << 16 | height) of the rotated buffer.
#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_rotate(
    mut env: JNIEnv,
    _class: JClass,
    src: JByteArray,
    width: jint,
    height: jint,
    bpp: jint,
    angle: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &src)?;
        let mut ob = vec![0u8; sb.len()];
        let (dw, dh) = crate::yuv::rotate(&sb, width as u32, height as u32, bpp as u32, angle as u32, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(((dw << 16) | dh) as jint)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_mirror(
    mut env: JNIEnv,
    _class: JClass,
    src: JByteArray,
    width: jint,
    height: jint,
    bpp: jint,
    flip_v: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &src)?;
        let mut ob = vec![0u8; sb.len()];
        crate::yuv::mirror(&sb, width as u32, height as u32, bpp as u32, flip_v != 0, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_scale(
    mut env: JNIEnv,
    _class: JClass,
    src: JByteArray,
    src_w: jint,
    src_h: jint,
    bpp: jint,
    dst_w: jint,
    dst_h: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &src)?;
        let mut ob = vec![0u8; sb.len()];
        crate::yuv::scale_bilinear(
            &sb, src_w as u32, src_h as u32, bpp as u32,
            dst_w as u32, dst_h as u32, &mut ob,
        )?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_crop(
    mut env: JNIEnv,
    _class: JClass,
    src: JByteArray,
    src_w: jint,
    src_h: jint,
    bpp: jint,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &src)?;
        let mut ob = vec![0u8; sb.len()];
        crate::yuv::crop(
            &sb, src_w as u32, src_h as u32, bpp as u32,
            x as u32, y as u32, w as u32, h as u32, &mut ob,
        )?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

// ---------------------------------------------------------------------------
// imgproc
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_brightnessContrast(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    _width: jint,
    _height: jint,
    brightness: jni::sys::jfloat,
    contrast: jni::sys::jfloat,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let mut buf = bytes(&env, &rgba)?;
        crate::imgproc::brightness_contrast(&mut buf, brightness, contrast)?;
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_saturationWarmth(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    _width: jint,
    _height: jint,
    saturation: jni::sys::jfloat,
    warmth: jni::sys::jfloat,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let mut buf = bytes(&env, &rgba)?;
        crate::imgproc::saturation_warmth(&mut buf, saturation, warmth)?;
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_applyCurve(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    curve: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let mut buf = bytes(&env, &rgba)?;
        let c = bytes(&env, &curve)?;
        if c.len() < 256 {
            return Err(crate::HoshiyomiError::BufferTooSmall);
        }
        let mut curve_arr = [0u8; 256];
        curve_arr.copy_from_slice(&c[..256]);
        crate::imgproc::apply_curve(&mut buf, &curve_arr)?;
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_colorMatrix(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    _width: jint,
    _height: jint,
    m: JFloatArray,
) -> jint {
    run!(env, || -> Result<jint, jni::errors::Error> {
        let mut marr = [0f32; 9];
        // The Kotlin side passes FloatArray; reading it as ints trips CheckJNI
        // ("incompatible array type float[] expected int[]").
        let mut tmp = [0f32; 9];
        env.get_float_array_region(&m, 0, &mut tmp)?;
        marr.copy_from_slice(&tmp);
        let mut buf = bytes(&env, &rgba)?;
        crate::imgproc::color_matrix(&mut buf, &marr)
            .map_err(|_| jni::errors::Error::JavaException)?;
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_gaussianBlur(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    width: jint,
    height: jint,
    radius: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &rgba)?;
        let mut ob = vec![0u8; sb.len()];
        crate::imgproc::gaussian_blur(&sb, width as u32, height as u32, radius as u32, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_vignette(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    width: jint,
    height: jint,
    strength: jni::sys::jfloat,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let mut buf = bytes(&env, &rgba)?;
        crate::imgproc::vignette(&mut buf, width as u32, height as u32, strength)?;
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_sharpen(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    width: jint,
    height: jint,
    amount: jni::sys::jfloat,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &rgba)?;
        let mut ob = vec![0u8; sb.len()];
        crate::imgproc::sharpen(&sb, width as u32, height as u32, amount, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

// ---------------------------------------------------------------------------
// docscan
// ---------------------------------------------------------------------------

/// Returns the sheet quad as flat [tlx, tly, trx, try_, brx, bry, blx, bly].
#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_detectSheet(
    mut env: JNIEnv,
    _class: JClass,
    grey: JByteArray,
    width: jint,
    height: jint,
) -> jni::sys::jfloatArray {
    let res = (|| -> Result<[f32; 8], crate::HoshiyomiError> {
        let gb = bytes(&env, &grey)?;
        let q = crate::docscan::detect_sheet(&gb, width as u32, height as u32)?;
        Ok([q.tl.x, q.tl.y, q.tr.x, q.tr.y, q.br.x, q.br.y, q.bl.x, q.bl.y])
    })();
    match res {
        Ok(flat) => env
            .new_float_array(8)
            .and_then(|arr| {
                env.set_float_array_region(&arr, 0, &flat)?;
                Ok(arr.into_raw())
            })
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Perspective-rectifies a quad into out_w x out_h bilinear RGBA output.
#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_rectifyDocument(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    width: jint,
    height: jint,
    quad: jni::objects::JFloatArray,
    out_w: jint,
    out_h: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, jni::errors::Error> {
        let sb = bytes(&env, &rgba)?;
        let mut qbuf = [0f32; 8];
        env.get_float_array_region(&quad, 0, &mut qbuf)?;
        let q = crate::docscan::Quad {
            tl: crate::docscan::Point { x: qbuf[0], y: qbuf[1] },
            tr: crate::docscan::Point { x: qbuf[2], y: qbuf[3] },
            br: crate::docscan::Point { x: qbuf[4], y: qbuf[5] },
            bl: crate::docscan::Point { x: qbuf[6], y: qbuf[7] },
        };
        let mut ob = vec![0u8; (out_w as usize) * (out_h as usize) * 4];
        crate::docscan::rectify(&sb, width as u32, height as u32, 4, q, out_w as u32, out_h as u32, &mut ob)
            .map_err(|_| jni::errors::Error::JavaException)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

/// Whitens document background and deepens ink; passes the white point.
#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_enhanceDocument(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    _width: jint,
    _height: jint,
    whitePoint: jint,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let mut buf = bytes(&env, &rgba)?;
        crate::docscan::enhance_document(&mut buf, whitePoint as u8);
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

/// Otsu threshold, exposed so the UI can show the document binarization.
/// Otsu threshold, exposed so the UI can show the document binarization.
#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_otsuThreshold(
    mut env: JNIEnv,
    _class: JClass,
    grey: JByteArray,
    width: jint,
    height: jint,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let gb = bytes(&env, &grey)?;
        if gb.len() < (width as usize) * (height as usize) {
            return Err(crate::HoshiyomiError::BufferTooSmall);
        }
        Ok(crate::docscan::otsu_threshold(&gb) as jint)
    }())
}

/// Multi-frame night stacking + shadow lift (SuperNight capture path).
#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_meanStack(
    mut env: JNIEnv,
    _class: JClass,
    frames: jni::objects::JObjectArray,
    width: jint,
    height: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, jni::errors::Error> {
        let n = env.get_array_length(&frames)? as usize;
        let mut bufs = Vec::with_capacity(n);
        for i in 0..n {
            let obj = env.get_object_array_element(&frames, i as i32)?;
            let arr: jni::objects::JByteArray = unsafe { jni::objects::JByteArray::from_raw(obj.into_raw()) };
            bufs.push(bytes(&env, &arr)?);
        }
        let refs: Vec<&[u8]> = bufs.iter().map(|b| b.as_slice()).collect();
        let mut ob = vec![0u8; (width as usize) * (height as usize) * 4];
        crate::imgproc::mean_stack(&refs, width as u32, height as u32, 4, &mut ob)
            .map_err(|_| jni::errors::Error::JavaException)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_nightLift(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    _width: jint,
    _height: jint,
    shadowPoint: jint,
    strength: jni::sys::jfloat,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let mut buf = bytes(&env, &rgba)?;
        crate::imgproc::night_lift(&mut buf, shadowPoint as u8, strength);
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

// ---------------------------------------------------------------------------
// beauty
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_skinSmooth(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    width: jint,
    height: jint,
    intensity: jni::sys::jfloat,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &rgba)?;
        let mut ob = vec![0u8; sb.len()];
        crate::beauty::skin_smooth(&sb, width as u32, height as u32, intensity, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_faceEnhance(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    width: jint,
    height: jint,
    fx: jni::sys::jfloat,
    fy: jni::sys::jfloat,
    fw: jni::sys::jfloat,
    fh: jni::sys::jfloat,
    brighten: jni::sys::jfloat,
    soften: jni::sys::jfloat,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let mut buf = bytes(&env, &rgba)?;
        crate::beauty::face_enhance(&mut buf, width as u32, height as u32, (fx, fy, fw, fh), brighten, soften)?;
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_beautyPass(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    width: jint,
    height: jint,
    faces: jni::objects::JFloatArray,
    smoothing: jni::sys::jfloat,
    brighten: jni::sys::jfloat,
    soften: jni::sys::jfloat,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &rgba)?;
        let mut ob = vec![0u8; sb.len()];
        let mut raw = vec![0f32; env.get_array_length(&faces)? as usize];
        env.get_float_array_region(&faces, 0, &mut raw)?;
        let faces: Vec<(f32, f32, f32, f32)> = raw
            .chunks_exact(4)
            .map(|c| (c[0], c[1], c[2], c[3]))
            .collect();
        crate::beauty::beauty_pass(&sb, width as u32, height as u32, &faces, smoothing, brighten, soften, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

// ---------------------------------------------------------------------------
// bokeh
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_depthOfField(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    depth: JByteArray,
    width: jint,
    height: jint,
    max_radius: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sb = bytes(&env, &rgba)?;
        let db = bytes(&env, &depth)?;
        let mut ob = vec![0u8; sb.len()];
        crate::bokeh::depth_of_field(&sb, &db, width as u32, height as u32, max_radius as u32, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_compositeBokeh(
    mut env: JNIEnv,
    _class: JClass,
    sharp: JByteArray,
    blurred: JByteArray,
    mask: JByteArray,
    width: jint,
    height: jint,
    edge_softness: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let sp = bytes(&env, &sharp)?;
        let bp = bytes(&env, &blurred)?;
        let mp = bytes(&env, &mask)?;
        let mut ob = vec![0u8; sp.len()];
        crate::bokeh::composite_bokeh(&sp, &bp, &mp, width as u32, height as u32, edge_softness as u32, &mut ob)?;
        put_bytes(&env, &out, &ob)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_portraitSpot(
    mut env: JNIEnv,
    _class: JClass,
    rgba: JByteArray,
    width: jint,
    height: jint,
    cx: jni::sys::jfloat,
    cy: jni::sys::jfloat,
    face_radius: jni::sys::jfloat,
    intensity: jni::sys::jfloat,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let mut buf = bytes(&env, &rgba)?;
        crate::bokeh::portrait_spot(&mut buf, width as u32, height as u32, cx, cy, face_radius, intensity)?;
        put_bytes(&env, &rgba, &buf)?;
        Ok(1)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_apertureRadius(
    _env: JNIEnv,
    _class: JClass,
    f_stop: jni::sys::jfloat,
    short_side: jint,
) -> jint {
    crate::bokeh::aperture_radius(f_stop, short_side as u32) as jint
}

// ---------------------------------------------------------------------------
// exif
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_exifOrientation(
    mut env: JNIEnv,
    _class: JClass,
    jpeg: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let jb = bytes(&env, &jpeg)?;
        let entries = crate::exif::parse(&jb)?;
        Ok(entries
            .iter()
            .find(|e| e.tag == crate::exif::tags::ORIENTATION)
            .and_then(|e| e.orientation_degrees())
            .unwrap_or(0) as jint)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_exifMakeModel(
    mut env: JNIEnv,
    _class: JClass,
    jpeg: JByteArray,
) -> jni::sys::jstring {
    let res = (|| -> Result<String, crate::HoshiyomiError> {
        let jb = bytes(&env, &jpeg)?;
        let entries = crate::exif::parse(&jb)?;
        let make = entries
            .iter()
            .find(|e| e.tag == crate::exif::tags::MAKE)
            .and_then(|e| e.string.clone())
            .unwrap_or_default();
        let model = entries
            .iter()
            .find(|e| e.tag == crate::exif::tags::MODEL)
            .and_then(|e| e.string.clone())
            .unwrap_or_default();
        Ok(format!("{} {}", make, model))
    })();
    match res {
        Ok(text) => env
            .new_string(&text)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_exifBuildApp1(
    mut env: JNIEnv,
    _class: JClass,
    tags_spec: JString,
) -> jni::sys::jbyteArray {
    let spec: String = env.get_string(&tags_spec).map(|s| s.into()).unwrap_or_default();
    let entries = parse_tag_spec(&spec);
    match crate::exif::build_app1(&entries) {
        Ok(app1) => env
            .byte_array_from_slice(&app1)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Parses `kind:tagname:value` records joined by commas.
fn parse_tag_spec(spec: &str) -> Vec<crate::exif::WriteTag> {
    use crate::exif::{tags, WriteTag};
    let mut out = Vec::new();
    for rec in spec.split(',') {
        let parts: Vec<&str> = rec.splitn(3, ':').collect();
        if parts.len() != 3 {
            continue;
        }
        let (kind, name, value) = (parts[0], parts[1], parts[2]);
        let tag = match name {
            "make" => tags::MAKE,
            "model" => tags::MODEL,
            "orientation" => tags::ORIENTATION,
            "datetime" => tags::DATE_TIME_ORIG,
            "gpslat" => tags::GPS_LATITUDE,
            "gpslatref" => tags::GPS_LATITUDE_REF,
            "gpslon" => tags::GPS_LONGITUDE,
            "gpslonref" => tags::GPS_LONGITUDE_REF,
            "iso" => tags::ISO,
            "fnumber" => tags::F_NUMBER,
            "focal" => tags::FOCAL_LENGTH,
            _ => continue,
        };
        let entry = match (kind, name) {
            ("a", "gpslat") => {
                let ns: Vec<u32> = value.split(' ').filter_map(|s| s.parse().ok()).collect();
                if ns.len() != 3 {
                    continue;
                }
                WriteTag::gps_coord(tag, ns[0], ns[1], ns[2])
            }
            ("a", _) => WriteTag::ascii(tag, value),
            ("r", _) => {
                let ns: Vec<u32> = value.split('/').filter_map(|s| s.parse().ok()).collect();
                if ns.len() != 2 {
                    continue;
                }
                WriteTag::rational(tag, ns[0], ns[1])
            }
            ("s", _) => WriteTag::short(tag, value.parse().unwrap_or(0)),
            ("l", _) => WriteTag::long(tag, value.parse().unwrap_or(0)),
            _ => continue,
        };
        out.push(entry);
    }
    out
}

// ---------------------------------------------------------------------------
// barcode
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_decodeQr(
    mut env: JNIEnv,
    _class: JClass,
    grey: JByteArray,
    width: jint,
    height: jint,
) -> jni::sys::jstring {
    // Camera frames are untrusted input: wrap in catch_unwind so a decoder
    // panic on a garbage frame returns "no code found" instead of aborting
    // the whole app process.
    let res: Result<Result<Option<String>, crate::HoshiyomiError>, Box<dyn std::any::Any + Send>> =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let gb = bytes(&env, &grey)?;
            Ok(crate::barcode::decode_frame(width as u32, height as u32, &gb)?
                .map(|q| q.text))
        }));
    match res {
        Ok(Ok(Some(text))) => env
            .new_string(&text)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        _ => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------------------
// facedet
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_detectFaces(
    mut env: JNIEnv,
    _class: JClass,
    grey: JByteArray,
    width: jint,
    height: jint,
) -> jni::sys::jfloatArray {
    let res = (|| -> Result<Vec<f32>, crate::HoshiyomiError> {
        let gb = bytes(&env, &grey)?;
        let faces = crate::facedet::detect_faces(width as u32, height as u32, &gb)?;
        Ok(faces.iter().flat_map(|f| [f.x, f.y, f.w, f.h]).collect())
    })();
    match res {
        Ok(flat) => env
            .new_float_array(flat.len() as i32)
            .and_then(|arr| {
                env.set_float_array_region(&arr, 0, &flat)?;
                Ok(arr.into_raw())
            })
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_faceMeteringBias(
    mut env: JNIEnv,
    _class: JClass,
    grey: JByteArray,
    width: jint,
    height: jint,
    faces: JIntArray,
) -> jni::sys::jfloat {
    let res = (|| -> Result<f32, jni::errors::Error> {
        let gb = bytes(&env, &grey)?;
        let n = env.get_array_length(&faces)? as usize / 4;
        let mut raw = vec![0i32; n * 4];
        env.get_int_array_region(&faces, 0, &mut raw)?;
        // Ints come in as fixed-point [0, 1000]
        let faces: Vec<crate::facedet::FaceRect> = raw
            .chunks_exact(4)
            .map(|c| crate::facedet::FaceRect {
                x: c[0] as f32 / 1000.0,
                y: c[1] as f32 / 1000.0,
                w: c[2] as f32 / 1000.0,
                h: c[3] as f32 / 1000.0,
                score: 1,
            })
            .collect();
        Ok(crate::facedet::face_metering_bias(width as u32, height as u32, &gb, &faces))
    })();
    res.unwrap_or(0.0)
}

// ---------------------------------------------------------------------------
// stitch
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_estimateDx(
    mut env: JNIEnv,
    _class: JClass,
    a: JByteArray,
    b: JByteArray,
    width: jint,
    height: jint,
    max_shift: jint,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let ab = bytes(&env, &a)?;
        let bb = bytes(&env, &b)?;
        Ok(crate::stitch::estimate_dx(&ab, &bb, width as u32, height as u32, max_shift as u32)? as jint)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_stitchPair(
    mut env: JNIEnv,
    _class: JClass,
    left: JByteArray,
    right: JByteArray,
    width: jint,
    height: jint,
    bpp: jint,
    dx: jint,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, crate::HoshiyomiError> {
        let lb = bytes(&env, &left)?;
        let rb = bytes(&env, &right)?;
        let mut ob = vec![0u8; lb.len() + rb.len()];
        let (dw, _) = crate::stitch::stitch_pair(
            &lb, &rb, width as u32, height as u32, bpp as u32, dx as i32, &mut ob,
        )?;
        put_bytes(&env, &out, &ob)?;
        Ok(dw as jint)
    }())
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_stitchHorizontal(
    mut env: JNIEnv,
    _class: JClass,
    frames: jni::objects::JObjectArray,
    width: jint,
    height: jint,
    bpp: jint,
    dxs: JIntArray,
    out: JByteArray,
) -> jint {
    run!(env, || -> Result<jint, jni::errors::Error> {
        let n = env.get_array_length(&frames)? as usize;
        let mut bufs = Vec::with_capacity(n);
        for i in 0..n {
            let obj = env.get_object_array_element(&frames, i as i32)?;
            let arr: jni::objects::JByteArray = unsafe { jni::objects::JByteArray::from_raw(obj.into_raw()) };
            bufs.push(bytes(&env, &arr)?);
        }
        let refs: Vec<&[u8]> = bufs.iter().map(|b| b.as_slice()).collect();
        let mut dxbuf = vec![0i32; n - 1];
        env.get_int_array_region(&dxs, 0, &mut dxbuf)?;
        let mut ob = vec![0u8; (width as usize) * (height as usize) * (bpp as usize) * n];
        let dw = crate::stitch::stitch_horizontal(
            &refs, width as u32, height as u32, bpp as u32, &dxbuf, &mut ob,
        )
        .map_err(|_| jni::errors::Error::JavaException)?;
        put_bytes(&env, &out, &ob)?;
        Ok(dw as jint)
    }())
}

/// Reserved native handle for panorama sessions (starts at 0, freed in
/// nativeFree).
pub type Handle = jlong;

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_nativeAlloc() -> jlong {
    0
}

#[no_mangle]
pub extern "system" fn Java_com_hoshiyomi_camera_native_NativeCore_nativeFree(_h: jlong) {}
