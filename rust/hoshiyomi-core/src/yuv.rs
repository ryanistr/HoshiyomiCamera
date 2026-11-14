//! YUV plane handling — replacement for TCAM's `libyuvutil.so`.
//!
//! Camera2 hands out [`PixelFormat::Yuv420p`] (three planes with independent row
//! strides) while GL, JPEG encoders and face detectors all want different layouts.
//! Every function here works on caller-owned slices with explicit strides so the
//! Java side passes Image plane buffers directly with zero copies.

use crate::{TcamError, TcamResult};

// ---------------------------------------------------------------------------
// BT.601 limited-range conversion (the range Camera2/AW produces).
// ---------------------------------------------------------------------------

const Y_MULT: i32 = 76309; // (1 << 16) * 1.164
const Y_OFFSET: i32 = 16 << 16;
const UV_R: i32 = 104597; // 1.596
const UV_G_U: i32 = -25675; // -0.391
const UV_G_V: i32 = -53279; // -0.813
const UV_B: i32 = 132201; // 2.018

#[inline]
fn clamp8(v: i32) -> u8 {
    v.clamp(0, 255) as u8
}

/// Convert one YUV 4:2:0 triple to an RGBA pixel, BT.601 limited range.
#[inline]
fn yuv_to_rgba(y: u8, u: u8, v: u8, alpha: u8) -> [u8; 4] {
    let y = (y as i32 * Y_MULT - Y_OFFSET) >> 16;
    let u = u as i32 - 128;
    let v = v as i32 - 128;
    let r = y + ((UV_R * v) >> 16);
    let g = y + ((UV_G_U * u + UV_G_V * v) >> 16);
    let b = y + ((UV_B * u) >> 16);
    [clamp8(r), clamp8(g), clamp8(b), alpha]
}

/// `YUV_420_888` → `RGBA_8888`. Strides are per-plane and need not equal width.
///
/// * `y`, `u`, `v` — plane buffers
/// * `y_stride`, `u_stride`, `v_stride` — row strides in **samples**
/// * `u_px`, `v_px` — horizontal pixel stride (1 for planar, 2 for interleaved
///   semi-planar encodings if the caller packed planes separately)
pub fn yuv420_to_rgba(
    width: u32,
    height: u32,
    y: &[u8],
    y_stride: u32,
    u: &[u8],
    u_stride: u32,
    u_px: u32,
    v: &[u8],
    v_stride: u32,
    v_px: u32,
    out: &mut [u8],
) -> TcamResult<()> {
    let (w, h) = (width as usize, height as usize);
    let (ys, us, vs) = (y_stride as usize, u_stride as usize, v_stride as usize);
    let (up, vp) = (u_px as usize, v_px as usize);
    if w == 0 || h == 0 {
        return Err(TcamError::BadDimensions);
    }
    let need = w * h * 4;
    if out.len() < need {
        return Err(TcamError::BufferTooSmall);
    }
    let chroma_rows = (h + 1) / 2;
    let chroma_w = (w + 1) / 2;
    // Bounds on the *highest index touched*, not stride*rows. Semi-planar HALs
    // (NV12-style interleaved UV) report the second chroma plane starting one
    // byte into the buffer, so its usable length is stride*rows − 1; requiring
    // the full planar size would reject a buffer this kernel reads just fine.
    let y_last = (h - 1) * ys + (w - 1);
    let u_last = (chroma_rows - 1) * us + (chroma_w - 1) * up;
    let v_last = (chroma_rows - 1) * vs + (chroma_w - 1) * vp;
    if y.len() <= y_last || u.len() <= u_last || v.len() <= v_last {
        return Err(TcamError::BufferTooSmall);
    }

    for j in 0..h {
        let chroma_j = j / 2;
        let row_y = j * ys;
        let row_u = chroma_j * us;
        let row_v = chroma_j * vs;
        let row_out = j * w * 4;
        for i in 0..w {
            let chroma_i = i / 2;
            let yv = y[row_y + i];
            let uv = u[row_u + chroma_i * up];
            let vv = v[row_v + chroma_i * vp];
            out[row_out + i * 4..row_out + i * 4 + 4]
                .copy_from_slice(&yuv_to_rgba(yv, uv, vv, 255));
        }
    }
    Ok(())
}

/// NV21 (Y + interleaved VU) → RGBA. Common from the legacy camera API and from
/// `ImageReader` when the producer chooses NV21.
pub fn nv21_to_rgba(width: u32, height: u32, src: &[u8], out: &mut [u8]) -> TcamResult<()> {
    let (w, h) = (width as usize, height as usize);
    let y_size = w * h;
    let chroma_rows = (h + 1) / 2;
    let chroma_w = (w + 1) / 2;
    if src.len() < y_size + chroma_rows * chroma_w * 2 {
        return Err(TcamError::BufferTooSmall);
    }
    if out.len() < y_size * 4 {
        return Err(TcamError::BufferTooSmall);
    }
    for j in 0..h {
        for i in 0..w {
            let ci = i / 2;
            let cj = j / 2;
            let yv = src[j * w + i];
            let vu_off = y_size + cj * chroma_w * 2 + ci * 2;
            let vv = src[vu_off];
            let uv = src[vu_off + 1];
            let p = (j * w + i) * 4;
            out[p..p + 4].copy_from_slice(&yuv_to_rgba(yv, uv, vv, 255));
        }
    }
    Ok(())
}

/// Reverse of [`nv21_to_rgba`] — RGBA → NV21, used when feeding the camera
/// pipeline from a GL readback.
pub fn rgba_to_nv21(width: u32, height: u32, src: &[u8], out: &mut [u8]) -> TcamResult<()> {
    let (w, h) = (width as usize, height as usize);
    let y_size = w * h;
    let chroma_rows = (h + 1) / 2;
    let chroma_w = (w + 1) / 2;
    if src.len() < y_size * 4 {
        return Err(TcamError::BufferTooSmall);
    }
    if out.len() < y_size + chroma_rows * chroma_w * 2 {
        return Err(TcamError::BufferTooSmall);
    }
    for j in 0..h {
        for i in 0..w {
            let p = (j * w + i) * 4;
            let (r, g, b) = (src[p] as i32, src[p + 1] as i32, src[p + 2] as i32);
            // RGB → Y (BT.601 limited) and downsampled chroma.
            let yv = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            out[j * w + i] = clamp8(yv);
            if (i & 1) == 0 && (j & 1) == 0 {
                let u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                let v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                let off = y_size + (j / 2) * chroma_w * 2 + (i / 2) * 2;
                out[off] = clamp8(v);
                out[off + 1] = clamp8(u);
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Geometric ops — operate in place or into a destination, on any 8-bpp format
// whose pixel stride is given.
// ---------------------------------------------------------------------------

/// Rotate a packed buffer by `angle` degrees (must be a multiple of 90, CCW).
/// `bpp` is bytes per pixel (1 = Y/grey, 4 = RGBA).
pub fn rotate(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    bpp: u32,
    angle: u32,
    out: &mut [u8],
) -> TcamResult<(u32, u32)> {
    if angle % 90 != 0 {
        return Err(TcamError::BadAngle);
    }
    let (sw, sh) = (src_w as usize, src_h as usize);
    let bpp = bpp as usize;
    if sw == 0 || sh == 0 {
        return Err(TcamError::BadDimensions);
    }
    if src.len() < sw * sh * bpp {
        return Err(TcamError::BufferTooSmall);
    }
    let turns = (angle / 90) % 4;
    let (dw, dh) = match turns {
        0 | 2 => (src_w, src_h),
        _ => (src_h, src_w),
    };
    let (dw, dh) = (dw as usize, dh as usize);
    if out.len() < dw * dh * bpp {
        return Err(TcamError::BufferTooSmall);
    }
    match turns {
        0 => out[..sw * sh * bpp].copy_from_slice(&src[..sw * sh * bpp]),
        1 => {
            // 90° CCW: dst(x,y) = src(x, sw-1-y)
            for y in 0..dh {
                for x in 0..dw {
                    let s = (x * sw + (sw - 1 - y)) * bpp;
                    let d = (y * dw + x) * bpp;
                    out[d..d + bpp].copy_from_slice(&src[s..s + bpp]);
                }
            }
        }
        2 => {
            for y in 0..sh {
                for x in 0..sw {
                    let s = (y * sw + x) * bpp;
                    let d = ((sh - 1 - y) * sw + (sw - 1 - x)) * bpp;
                    out[d..d + bpp].copy_from_slice(&src[s..s + bpp]);
                }
            }
        }
        _ => {
            // 270° CCW (90° CW): dst(x,y) = src(sh-1-x, y)
            for y in 0..dh {
                for x in 0..dw {
                    let s = ((sh - 1 - x) * sw + y) * bpp;
                    let d = (y * dw + x) * bpp;
                    out[d..d + bpp].copy_from_slice(&src[s..s + bpp]);
                }
            }
        }
    }
    Ok((dw as u32, dh as u32))
}

/// Mirror (flip) a packed buffer. `flip_v` false = horizontal mirror (front-camera
/// preview), true = vertical.
pub fn mirror(
    src: &[u8],
    width: u32,
    height: u32,
    bpp: u32,
    flip_v: bool,
    out: &mut [u8],
) -> TcamResult<()> {
    let (w, h) = (width as usize, height as usize);
    let bpp = bpp as usize;
    if src.len() < w * h * bpp || out.len() < w * h * bpp {
        return Err(TcamError::BufferTooSmall);
    }
    if flip_v {
        for y in 0..h {
            let s = y * w * bpp;
            let d = (h - 1 - y) * w * bpp;
            out[d..d + w * bpp].copy_from_slice(&src[s..s + w * bpp]);
        }
    } else {
        for y in 0..h {
            let row = y * w * bpp;
            for x in 0..w / 2 {
                let a = row + x * bpp;
                let b = row + (w - 1 - x) * bpp;
                for k in 0..bpp {
                    out[a + k] = src[b + k];
                    out[b + k] = src[a + k];
                }
            }
            if w % 2 == 1 {
                let m = row + (w / 2) * bpp;
                out[m..m + bpp].copy_from_slice(&src[m..m + bpp]);
            }
        }
    }
    Ok(())
}

/// Bilinear scale of a packed buffer. Used for thumbnail generation (TCAM's
/// "fast thumb" surface) and preview-size reduction before face detect.
pub fn scale_bilinear(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    bpp: u32,
    dst_w: u32,
    dst_h: u32,
    out: &mut [u8],
) -> TcamResult<()> {
    let (sw, sh, dw, dh) = (
        src_w as usize, src_h as usize, dst_w as usize, dst_h as usize,
    );
    let bpp = bpp as usize;
    if src.len() < sw * sh * bpp || out.len() < dw * dh * bpp {
        return Err(TcamError::BufferTooSmall);
    }
    if dw == 0 || dh == 0 {
        return Err(TcamError::BadDimensions);
    }
    // Fixed-point sampling: map dst coords onto src, edge-inclusive.
    let x_ratio = (sw as u64).wrapping_mul(1 << 16) / dw as u64;
    let y_ratio = (sh as u64).wrapping_mul(1 << 16) / dh as u64;
    for dy in 0..dh {
        let sy = ((dy as u64) * y_ratio) >> 16;
        let sy = (sy as usize).min(sh - 1);
        let sy2 = (sy + 1).min(sh - 1);
        let fy = (((dy as u64) * y_ratio) & 0xFFFF) as u32;
        for dx in 0..dw {
            let sx = ((dx as u64) * x_ratio) >> 16;
            let sx = (sx as usize).min(sw - 1);
            let sx2 = (sx + 1).min(sw - 1);
            let fx = (((dx as u64) * x_ratio) & 0xFFFF) as u32;
            let d = (dy * dw + dx) * bpp;
            for k in 0..bpp {
                let p00 = src[(sy * sw + sx) * bpp + k] as i32;
                let p01 = src[(sy * sw + sx2) * bpp + k] as i32;
                let p10 = src[(sy2 * sw + sx) * bpp + k] as i32;
                let p11 = src[(sy2 * sw + sx2) * bpp + k] as i32;
                let top = p00 + ((p01 - p00) * fx as i32 >> 16);
                let bot = p10 + ((p11 - p10) * fx as i32 >> 16);
                out[d + k] = (top + ((bot - top) * fy as i32 >> 16)).clamp(0, 255) as u8;
            }
        }
    }
    Ok(())
}

/// Crop a packed rectangle out of a packed frame.
pub fn crop(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    bpp: u32,
    x: u32,
    y: u32,
    w: u32,
    h: u32,
    out: &mut [u8],
) -> TcamResult<()> {
    let (sw, sh) = (src_w as usize, src_h as usize);
    let (cx, cy, cw, ch) = (x as usize, y as usize, w as usize, h as usize);
    let bpp = bpp as usize;
    if cx + cw > sw || cy + ch > sh {
        return Err(TcamError::OutOfRange);
    }
    if src.len() < sw * sh * bpp || out.len() < cw * ch * bpp {
        return Err(TcamError::BufferTooSmall);
    }
    for r in 0..ch {
        let s = ((cy + r) * sw + cx) * bpp;
        let d = r * cw * bpp;
        out[d..d + cw * bpp].copy_from_slice(&src[s..s + cw * bpp]);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gradient_nv21(w: usize, h: usize) -> Vec<u8> {
        let y_size = w * h;
        let mut buf = vec![0u8; y_size + ((h + 1) / 2) * ((w + 1) / 2) * 2];
        for j in 0..h {
            for i in 0..w {
                buf[j * w + i] = ((i + j) % 256) as u8;
            }
        }
        buf
    }

    #[test]
    fn nv21_rgba_roundtrip_size() {
        let (w, h) = (64, 48);
        let src = gradient_nv21(w, h);
        let mut rgba = vec![0u8; w * h * 4];
        nv21_to_rgba(w as u32, h as u32, &src, &mut rgba).unwrap();
        // Non-constant output means the color path ran.
        assert!(rgba.windows(4).any(|px| px[0] != 0 || px[1] != 0 || px[2] != 0));
    }

    #[test]
    fn rgba_nv21_roundtrip_midgray() {
        let (w, h) = (32, 32);
        let mut rgba = vec![0u8; w * h * 4];
        for px in rgba.chunks_exact_mut(4) {
            px[0] = 128;
            px[1] = 128;
            px[2] = 128;
            px[3] = 255;
        }
        let mut nv21 = vec![0u8; w * h + (h / 2) * (w / 2) * 2];
        rgba_to_nv21(w as u32, h as u32, &rgba, &mut nv21).unwrap();
        // Mid-grey must stay close to the neutral point after round-trip.
        let y = (nv21[0] as i32 - 128).abs();
        assert!(y <= 8, "Y drifted: {}", nv21[0]);
    }

    #[test]
    fn rotate_180_is_identity_of_pixels() {
        // Two 90° rotations equal one 180° rotation, not the identity.
        let (w, h) = (8, 6);
        let src: Vec<u8> = (0..w * h).map(|i| i as u8).collect();
        let mut once = vec![0u8; w * h];
        let mut twice = vec![0u8; w * h];
        rotate(&src, w as u32, h as u32, 1, 90, &mut once).unwrap();
        let (dw, dh) = rotate(&once, h as u32, w as u32, 1, 90, &mut twice).unwrap();
        assert_eq!((dw, dh), (w as u32, h as u32));
        let mut rotated180 = vec![0u8; w * h];
        rotate(&src, w as u32, h as u32, 1, 180, &mut rotated180).unwrap();
        assert_eq!(twice, rotated180);
    }

    #[test]
    fn rotate_90_swaps_dims() {
        let (w, h) = (8, 6);
        let src = vec![7u8; w * h];
        let mut out = vec![0u8; w * h];
        let (dw, dh) = rotate(&src, w as u32, h as u32, 1, 90, &mut out).unwrap();
        assert_eq!((dw, dh), (h as u32, w as u32));
    }

    #[test]
    fn mirror_h_flips_row() {
        let (w, h) = (4, 1);
        let src = [1u8, 2, 3, 4];
        let mut out = [0u8; 4];
        mirror(&src, w, h, 1, false, &mut out).unwrap();
        assert_eq!(out, [4, 3, 2, 1]);
    }

    #[test]
    fn mirror_is_involution() {
        let (w, h) = (16, 9);
        let src: Vec<u8> = (0..w * h).map(|i| (i * 7) as u8).collect();
        let mut once = vec![0u8; w * h];
        let mut twice = vec![0u8; w * h];
        mirror(&src, w as u32, h as u32, 1, false, &mut once).unwrap();
        mirror(&once, w as u32, h as u32, 1, false, &mut twice).unwrap();
        assert_eq!(twice, src);
    }

    #[test]
    fn identity_scale_copies() {
        let (w, h) = (10, 10);
        let src: Vec<u8> = (0..w * h).map(|i| i as u8).collect();
        let mut out = vec![0u8; w * h];
        scale_bilinear(&src, w as u32, h as u32, 1, w as u32, h as u32, &mut out).unwrap();
        assert_eq!(out, src);
    }

    #[test]
    fn half_scale_preserves_corner_values() {
        let (w, h) = (20, 20);
        let mut src = vec![0u8; w * h];
        src[0] = 200;
        src[w * h - 1] = 50;
        let mut out = vec![0u8; (w / 2) * (h / 2)];
        scale_bilinear(&src, w as u32, h as u32, 1, 10, 10, &mut out).unwrap();
        assert!(out[0] >= 100);
        assert!(out[out.len() - 1] <= 100);
    }

    #[test]
    fn crop_extracts_region() {
        let (w, h) = (10, 10);
        let src: Vec<u8> = (0..w * h).map(|i| i as u8).collect();
        let mut out = vec![0u8; 4];
        crop(&src, w, h, 1, 2, 3, 4, 1, &mut out).unwrap();
        assert_eq!(out, [32, 33, 34, 35]);
    }

    #[test]
    fn rejects_bad_args() {
        let src = vec![0u8; 16];
        let mut out = vec![0u8; 16];
        assert_eq!(
            rotate(&src, 4, 4, 1, 45, &mut out).unwrap_err(),
            TcamError::BadAngle
        );
        assert_eq!(
            rotate(&src, 0, 4, 1, 90, &mut out).unwrap_err(),
            TcamError::BadDimensions
        );
        assert_eq!(
            crop(&src, 4, 4, 1, 3, 3, 4, 4, &mut out).unwrap_err(),
            TcamError::OutOfRange
        );
    }

    #[test]
    fn yuv420_planar_path() {
        let (w, h) = (32, 16);
        let y: Vec<u8> = (0..w * h).map(|i| (i % 251) as u8).collect();
        let u = vec![128u8; (w / 2) * (h / 2)];
        let v = vec![128u8; (w / 2) * (h / 2)];
        let mut out = vec![0u8; w * h * 4];
        yuv420_to_rgba(w as u32, h as u32, &y, w as u32, &u, w as u32 / 2, 1, &v, w as u32 / 2, 1, &mut out)
            .unwrap();
        // Neutral chroma (u=v=128) must produce a neutral/greyscale RGB triple.
        for px in out.chunks_exact(4) {
            assert_eq!(px[0], px[1]);
            assert_eq!(px[1], px[2]);
            assert_eq!(px[3], 255);
        }
    }

    #[test]
    fn yuv420_semoplanar_hal_shape_is_accepted() {
        // Shape observed on a live MediaTek HAL: 640x480, chroma interleaved
        // (pixel stride 2) with each chroma view reporting stride*rows − 1
        // usable bytes because the V view starts one byte into the plane.
        let (w, h) = (64, 48);
        let ys = w;
        let us = w; // interleaved: a chroma row pair spans the full width
        let chroma_rows = (h + 1) / 2;
        let y: Vec<u8> = (0..w * h).map(|i| (i % 251) as u8).collect();
        let plane = vec![128u8; us * chroma_rows];
        let u = &plane[..plane.len() - 1];
        let v = &plane[1..];
        let mut out = vec![0u8; w * h * 4];
        yuv420_to_rgba(w as u32, h as u32, &y, ys as u32, u, us as u32, 2, v, us as u32, 2, &mut out)
            .expect("semi-planar buffers covering every indexed sample must pass");
        for px in out.chunks_exact(4) {
            assert_eq!(px[3], 255);
        }
    }

    #[test]
    fn yuv420_rejects_chroma_buffer_short_of_last_index() {
        let (w, h) = (64, 48);
        let y = vec![100u8; w * h];
        // One byte short of the last indexed chroma sample: covers every row
        // but the final pixel of the last row.
        let len = ((h + 1) / 2 - 1) * w + ((w + 1) / 2 - 1) * 2;
        let u = vec![128u8; len];
        let v = vec![128u8; len];
        let mut out = vec![0u8; w * h * 4];
        let err = yuv420_to_rgba(w as u32, h as u32, &y, w as u32, &u, w as u32, 2, &v, w as u32, 2, &mut out)
            .unwrap_err();
        assert!(matches!(err, TcamError::BufferTooSmall));
    }
}
