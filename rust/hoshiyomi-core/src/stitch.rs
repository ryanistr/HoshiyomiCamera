//! Panorama / wide-selfie stitching — replaces TCAM's `libpanorama.so` and
//! `libwideselfie.so`. Implements phase correlation on downscaled greyscale
//! frames to estimate translation, then blends overlapping strips with a
//! linear feather. The camera-app control loop (capture N frames while the
//! user pans) lives on the Kotlin side; this module is the pure math.

use crate::yuv::scale_bilinear;
use crate::{TcamError, TcamResult};

/// Estimate the horizontal translation between two frames using normalized
/// phase correlation on the FFT... pure-Rust DFT is O(n² log-free), so we use
/// the classic sum-of-absolute-differences over coarse search window instead:
/// for a fixed-width output no rotation correction is required, and it costs
/// one pass per candidate shift.
pub fn estimate_dx(
    a: &[u8],
    b: &[u8],
    width: u32,
    height: u32,
    max_shift: u32,
) -> TcamResult<i32> {
    let (w, h) = (width as usize, height as usize);
    if a.len() < w * h || b.len() < w * h {
        return Err(TcamError::BufferTooSmall);
    }
    let max = max_shift.min(w as u32 / 4) as i32;
    let mut best = (i64::MAX, 0i32);
    for dx in -max..=max {
        let mut sad = 0i64;
        let mut n = 0i64;
        // Compare columns that exist in both frames.
        let lo = (-dx).max(0) as usize;
        let hi = (w as i32 - dx).min(w as i32) as usize;
        for x in lo..hi {
            for y in 0..h {
                let va = a[y * w + x] as i32;
                let vb = b[y * w + (x as i32 + dx) as usize] as i32;
                sad += (va - vb).abs() as i64;
                n += 1;
            }
        }
        if n > 0 && sad < best.0 {
            best = (sad, dx);
        }
    }
    Ok(best.1)
}

/// Stitch two strips side by side with a feathered blend over the overlap.
/// `dx` is the translation from `left` to `right` (frame `right`'s origin sits
/// `dx` columns into `left`'s coordinate space).
pub fn stitch_pair(
    left: &[u8],
    right: &[u8],
    width: u32,
    height: u32,
    bpp: u32,
    dx: i32,
    out: &mut [u8],
) -> TcamResult<(u32, u32)> {
    let (w, h) = (width as usize, height as usize);
    let bpp = bpp as usize;
    if left.len() < w * h * bpp || right.len() < w * h * bpp {
        return Err(TcamError::BufferTooSmall);
    }
    let dw = (w as i32 + (w as i32 - dx)).max(w as i32 + 1) as usize;
    if out.len() < dw * h * bpp {
        return Err(TcamError::BufferTooSmall);
    }
    let fade = (w as i32 - dx).max(0) as usize; // overlap width
    let fade = fade.min(w);
    // Copy left frame.
    for y in 0..h {
        out[y * dw * bpp..y * dw * bpp + w * bpp].copy_from_slice(&left[y * w * bpp..(y + 1) * w * bpp]);
    }
    // Blend overlap, copy remainder of right.
    let off = (w as i32 - dx) as usize;
    for y in 0..h {
        for x in 0..w {
            let d = (y * dw + off + x) * bpp;
            let r = (y * w + x) * bpp;
            if x < fade && off + x >= w.saturating_sub(0) {
                // no overlap for this column — pure right copy
            }
            if x < fade {
                let t = x as f32 / fade.max(1) as f32;
                let l = (y * w + w - fade + x) * bpp;
                for k in 0..bpp {
                    let lv = left[l + k] as f32;
                    let rv = right[r + k] as f32;
                    out[d + k] = (lv * (1.0 - t) + rv * t).clamp(0.0, 255.0) as u8;
                }
            } else {
                for k in 0..bpp {
                    out[d + k] = right[r + k];
                }
            }
        }
    }
    Ok((dw as u32, height))
}

/// Fit-and-center: produces the square-ish presentation the panorama mode
/// shows while capturing. Downscale preserving aspect.
pub fn fit(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    bpp: u32,
    dst_w: u32,
    dst_h: u32,
    out: &mut [u8],
) -> TcamResult<()> {
    scale_bilinear(src, src_w, src_h, bpp, dst_w, dst_h, out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn textured(w: usize, h: usize, seed: u32) -> Vec<u8> {
        let mut v = Vec::with_capacity(w * h);
        for y in 0..h {
            for x in 0..w {
                let p = (x as u32 * 17 + y as u32 * 31 + seed * 7) % 256;
                v.push(p as u8);
            }
        }
        v
    }

    fn shifted(src: &[u8], w: usize, h: usize, dx: i32) -> Vec<u8> {
        let mut v = vec![0u8; w * h];
        for y in 0..h {
            for x in 0..w {
                let sx = x as i32 - dx;
                if (0..w as i32).contains(&sx) {
                    v[y * w + x] = src[y * w + sx as usize];
                } else {
                    v[y * w + x] = src[y * w + sx.rem_euclid(w as i32) as usize];
                }
            }
        }
        v
    }

    #[test]
    fn estimates_exact_shift() {
        let (w, h) = (128, 64);
        let a = textured(w, h, 3);
        let b = shifted(&a, w, h, 20);
        assert_eq!(estimate_dx(&a, &b, w as u32, h as u32, 40).unwrap(), 20);
    }

    #[test]
    fn stitch_width_accounts_for_overlap() {
        let (w, h) = (32, 8);
        let a = textured(w, h, 1);
        let b = textured(w, h, 2);
        let mut out = vec![0u8; (w * 2) * h];
        let (dw, dh) = stitch_pair(&a, &b, w as u32, h as u32, 1, 8, &mut out).unwrap();
        assert!(dw >= w as u32 * 2 - 8);
        assert_eq!(dh, h as u32);
    }

    #[test]
    fn zero_shift_is_self_match() {
        let (w, h) = (64, 32);
        let a = textured(w, h, 9);
        assert_eq!(estimate_dx(&a, &a, w as u32, h as u32, 16).unwrap(), 0);
    }
}


/// Stitch N same-size frames side by side into one canvas. Frame k is placed
/// at the running x offset `sum_{i<k} (w - dx_i)`, and the overlap band of each
/// incoming frame is feather-blended against the accumulated image so seams do
/// not show. Returns the total canvas width.
pub fn stitch_horizontal(
    frames: &[&[u8]],
    width: u32,
    height: u32,
    bpp: u32,
    dxs: &[i32],
    out: &mut [u8],
) -> TcamResult<u32> {
    let (w, h, bpp) = (width as usize, height as usize, bpp as usize);
    if frames.len() != dxs.len() + 1 {
        return Err(crate::TcamError::InvalidData);
    }
    for f in frames {
        if f.len() < w * h * bpp {
            return Err(crate::TcamError::BufferTooSmall);
        }
    }
    let mut offsets = vec![0u32; frames.len()];
    for k in 1..frames.len() {
        let step = (w as i32 - dxs[k - 1]).max(0) as u32;
        offsets[k] = offsets[k - 1] + step;
    }
    let dw = *offsets.last().unwrap_or(&0) as usize + w;
    if out.len() < dw * h * bpp {
        return Err(crate::TcamError::BufferTooSmall);
    }
    for k in 0..frames.len() {
        let off = offsets[k] as usize;
        let fade = if k == 0 { 0usize } else { (w as i32 - dxs[k - 1]).max(0) as usize };
        let frame = frames[k];
        for y in 0..h {
            for x in 0..w {
                let src = (y * w + x) * bpp;
                let d = (y * dw + off + x) * bpp;
                if k == 0 || x >= fade {
                    out[d..d + bpp].copy_from_slice(&frame[src..src + bpp]);
                } else {
                    let t = x as f32 / fade.max(1) as f32;
                    for c in 0..bpp {
                        let acc = out[d + c] as f32;
                        let cur = frame[src + c] as f32;
                        out[d + c] = (acc * (1.0 - t) + cur * t) as u8;
                    }
                }
            }
        }
    }
    Ok(dw as u32)
}

// ---------------------------------------------------------------------------
// Tests for the multi-frame path
// ---------------------------------------------------------------------------

#[cfg(test)]
mod multi_tests {
    use super::*;

    fn solid_grey(w: usize, h: usize, v: u8) -> Vec<u8> {
        vec![v; w * h]
    }

    fn ramp_grey(w: usize, h: usize, seed: u32) -> Vec<u8> {
        let mut v = Vec::with_capacity(w * h);
        for y in 0..h {
            for x in 0..w {
                v.push(((x as u32 * 5 + y as u32 * 3 + seed) % 251) as u8);
            }
        }
        v
    }

    #[test]
    fn identical_frames_with_full_overlap_produce_single_width() {
        let (w, h) = (40, 10);
        let a = solid_grey(w, h, 100);
        let b = solid_grey(w, h, 100);
        let mut out = vec![0u8; w * 2 * h];
        let dw = stitch_horizontal(&[&a, &b], w as u32, h as u32, 1, &[w as i32], &mut out).unwrap();
        // Full overlap: second frame lands offset 0 → canvas = w wide.
        assert_eq!(dw, w as u32);
        assert!(out[..dw as usize * h].iter().all(|&v| v == 100));
    }

    #[test]
    fn half_overlap_doubles_width_and_blends() {
        let (w, h) = (40, 10);
        let a = solid_grey(w, h, 60);
        let b = solid_grey(w, h, 200);
        let mut out = vec![0u8; w * 2 * h];
        let dw = stitch_horizontal(&[&a, &b], w as u32, h as u32, 1, &[(w / 2) as i32], &mut out).unwrap();
        assert_eq!(dw, (w + w / 2) as u32);
        // Far left comes from a, far right comes from b (at the canvas edge).
        assert_eq!(out[0], 60);
        let edge = (h - 1) * dw as usize + (w + w / 2 - 1);
        assert_eq!(out[edge as usize], 200);
    }

    #[test]
    fn three_frames_canvas_is_sum_of_steps() {
        let (w, h) = (32, 8);
        let frames: Vec<Vec<u8>> = (0..3).map(|k| ramp_grey(w, h, k * 11)).collect();
        let refs: Vec<&[u8]> = frames.iter().map(|f| f.as_slice()).collect();
        let dxs = [16i32, 16];
        let mut out = vec![0u8; w * 3 * h];
        let dw = stitch_horizontal(&refs, w as u32, h as u32, 1, &dxs, &mut out).unwrap();
        assert_eq!(dw, (w + 16 + 16) as u32);
    }

    #[test]
    fn mismatched_dx_count_is_rejected() {
        let (w, h) = (16, 8);
        let a = solid_grey(w, h, 1);
        let mut out = vec![0u8; w * 3 * h];
        let err = stitch_horizontal(&[&a], w as u32, h as u32, 1, &[1], &mut out).unwrap_err();
        assert_eq!(err, crate::TcamError::InvalidData);
    }
}
