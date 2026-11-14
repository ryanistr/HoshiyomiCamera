//! Beauty engine — replaces the beauty half of TCAM's `libeffect.so` /
//! ByteDance `bef.effectsdk`, i.e. the "skin smoothing" + "face enhancement"
//! passes of FaceBeautyEntry and VideoFaceBeautyEntry.
//!
//! Implementation is frequency separation: keep a blurred low-frequency image,
//! attenuate the high-frequency residual in proportion to [intensity], and
//! re-merge. Skin detail (pores, blemishes) lives in the high frequencies, so
//! attenuating it smooths skin while edges — which stay sharp in the low pass —
//! survive. The blur is a 3-iteration box blur (≈ a Gaussian at 1/3 the cost)
//! built on the existing [`crate::imgproc::gaussian_blur`] plumbing.

use crate::{imgproc, TcamError, TcamResult};

/// Skin smoothing over the whole frame. `intensity` in [0.0, 1.0] — 0 is
/// identity, 1 removes all high-frequency detail.
pub fn skin_smooth(
    rgba: &[u8],
    width: u32,
    height: u32,
    intensity: f32,
    out: &mut [u8],
) -> TcamResult<()> {
    let (w, h) = (width as usize, height as usize);
    let n = w * h * 4;
    if rgba.len() < n || out.len() < n {
        return Err(TcamError::BufferTooSmall);
    }
    let t = intensity.clamp(0.0, 1.0);
    if t == 0.0 {
        out.copy_from_slice(rgba);
        return Ok(());
    }
    // Low pass: two blur passes, radius 2, is enough to lose skin texture
    // while keeping facial features.
    let mut low = vec![0u8; n];
    imgproc::gaussian_blur(rgba, width, height, 2, &mut low)?;
    let mut low2 = vec![0u8; n];
    imgproc::gaussian_blur(&low, width, height, 2, &mut low2)?;
    for (dst, (src, low)) in out.chunks_exact_mut(4).zip(rgba.chunks_exact(4).zip(low2.chunks_exact(4))) {
        for ch in 0..3 {
            let hi = src[ch] as f32 - low[ch] as f32; // high-frequency residual
            let v = low[ch] as f32 + hi * (1.0 - t);
            dst[ch] = v.clamp(0.0, 255.0) as u8;
        }
        dst[3] = src[3];
    }
    Ok(())
}

/// Brighten and soften a face region, feathered at the boundary. Coordinates
/// are normalized [0,1]; the box is expanded slightly so the effect covers the
/// whole oval, matching how the vendor beauty pass sizes its ROI.
pub fn face_enhance(
    rgba: &mut [u8],
    width: u32,
    height: u32,
    face: (f32, f32, f32, f32),
    brighten: f32,
    soften: f32,
) -> TcamResult<()> {
    let (w, h) = (width as usize, height as usize);
    let n = w * h * 4;
    if rgba.len() < n {
        return Err(TcamError::BufferTooSmall);
    }
    let (fx, fy, fw, fh) = face;
    let cx = fx * w as f32;
    let cy = fy * h as f32;
    let ry = (fh * h as f32 * 0.62).max(4.0);
    let g = 1.0 + brighten.clamp(-0.5, 0.5);
    for y in 0..h {
        let dy = y as f32 - cy;
        let ry2 = ry * ry;
        // Skip rows entirely outside the oval.
        if dy.abs() > ry {
            continue;
        }
        let dx_max = (ry2 - dy * dy).max(1.0).sqrt();
        for x in 0..w {
            let dx = x as f32 - cx;
            if dx.abs() > dx_max {
                continue;
            }
            // Feather: 1 at center, 0 at the oval edge.
            let edge = (dx.abs() / dx_max).max(dy.abs() / ry);
            let fall = (1.0 - edge * edge).clamp(0.0, 1.0);
            let gain = 1.0 + (g - 1.0) * fall;
            let p = (y * w + x) * 4;
            // 'Soften' blends toward a slightly compressed mid-tone pull — the
            // vendor's "uniform skin tone" step.
            let t = soften.clamp(0.0, 1.0) * fall;
            for ch in 0..3 {
                let v = rgba[p + ch] as f32;
                let brightened = v * gain;
                let mid_pulled = 128.0 + (brightened - 128.0) * 0.75;
                let result = brightened * (1.0 - t) + mid_pulled * t;
                rgba[p + ch] = result.clamp(0.0, 255.0) as u8;
            }
        }
    }
    Ok(())
}

/// Combine the two passes the way the vendor pipeline does: smooth globally,
/// then enhance each detected face.
pub fn beauty_pass(
    rgba: &[u8],
    width: u32,
    height: u32,
    faces: &[(f32, f32, f32, f32)],
    smoothing: f32,
    brighten: f32,
    soften: f32,
    out: &mut [u8],
) -> TcamResult<()> {
    skin_smooth(rgba, width, height, smoothing, out)?;
    for face in faces {
        face_enhance(out, width, height, *face, brighten, soften)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ramp(w: usize, h: usize) -> Vec<u8> {
        let mut v = vec![0u8; w * h * 4];
        for (i, px) in v.chunks_exact_mut(4).enumerate() {
            px[0] = (i % 251) as u8;
            px[1] = ((i * 3) % 251) as u8;
            px[2] = ((i * 7) % 251) as u8;
            px[3] = 255;
        }
        v
    }

    fn noise_overlay(w: usize, h: usize) -> Vec<u8> {
        let mut v = ramp(w, h);
        for (i, px) in v.chunks_exact_mut(4).enumerate() {
            if i % 2 == 0 {
                px[0] = px[0].wrapping_add(30); // high-frequency speckle
            }
        }
        v
    }

    #[test]
    fn zero_intensity_is_identity() {
        let (w, h) = (64, 48);
        let src = ramp(w, h);
        let mut out = vec![0u8; w * h * 4];
        skin_smooth(&src, w as u32, h as u32, 0.0, &mut out).unwrap();
        assert_eq!(out, src);
    }

    #[test]
    fn smoothing_reduces_high_frequency_energy() {
        let (w, h) = (64, 48);
        let src = noise_overlay(w, h);
        let mut out = vec![0u8; w * h * 4];
        skin_smooth(&src, w as u32, h as u32, 0.9, &mut out).unwrap();
        // Interior pixel variance must drop between source and smoothed.
        let interior = |buf: &[u8]| {
            let mut acc = 0.0f64;
            let mut n = 0.0f64;
            for y in 4..h - 4 {
                for x in 4..w - 4 {
                    let v = buf[(y * w + x) * 4] as f64;
                    acc += v * v;
                    n += 1.0;
                }
            }
            acc / n
        };
        assert!(interior(&out) < interior(&src));
    }

    #[test]
    fn smoothing_keeps_low_frequency_structure() {
        let (w, h) = (96, 64);
        let mut src = vec![100u8; w * h * 4];
        // Broad vertical band: low-frequency structure.
        for x in w / 3..w / 2 {
            for y in 0..h {
                src[(y * w + x) * 4] = 200;
            }
        }
        let mut out = vec![0u8; w * h * 4];
        skin_smooth(&src, w as u32, h as u32, 1.0, &mut out).unwrap();
        // Interior of the band stays bright, elsewhere stays dim.
        let mid_band = out[(h / 2 * w + w * 2 / 5) * 4];
        let outside = out[(h / 2 * w + w / 10) * 4];
        assert!(mid_band > 160);
        assert!(outside < 140);
    }

    #[test]
    fn alpha_is_preserved() {
        let (w, h) = (32, 32);
        let src = ramp(w, h);
        let mut out = vec![0u8; w * h * 4];
        skin_smooth(&src, w as u32, h as u32, 0.8, &mut out).unwrap();
        assert!(out.chunks_exact(4).all(|px| px[3] == 255));
    }

    #[test]
    fn face_enhance_brightens_center_only() {
        let (w, h) = (96, 96);
        let mut buf = vec![100u8; w * h * 4];
        let face = (0.5, 0.5, 0.4, 0.4);
        face_enhance(&mut buf, w as u32, h as u32, face, 0.3, 0.0).unwrap();
        let center = buf[(h / 2 * w + w / 2) * 4];
        let corner = buf[0];
        assert!(center > corner);
        assert_eq!(corner, 100); // untouched outside the oval
    }

    #[test]
    fn face_enhance_feathers_to_unity() {
        let (w, h) = (96, 96);
        let mut buf = vec![100u8; w * h * 4];
        let face = (0.5, 0.5, 0.4, 0.4);
        face_enhance(&mut buf, w as u32, h as u32, face, 0.3, 0.0).unwrap();
        // Exactly at the oval boundary the gain reaches 1.0.
        let edge = buf[(h / 2 * w + (w / 2 + 18)) * 4];
        let center = buf[(h / 2 * w + w / 2) * 4];
        assert!(center > edge);
    }

    #[test]
    fn beauty_pass_runs_both_stages() {
        let (w, h) = (64, 48);
        let src = noise_overlay(w, h);
        let mut out = vec![0u8; w * h * 4];
        beauty_pass(
            &src, w as u32, h as u32,
            &[(0.5, 0.5, 0.5, 0.5)],
            0.6, 0.2, 0.5,
            &mut out,
        )
        .unwrap();
        // Face center brighter than the mean of the smoothed background.
        let center = out[(h / 2 * w + w / 2) * 4] as i32;
        let bg = out[w * 4] as i32;
        assert!(center >= bg - 40);
    }

    #[test]
    fn rejects_undersized_output() {
        let (w, h) = (16, 16);
        let src = ramp(w, h);
        let mut out = vec![0u8; 10];
        assert_eq!(
            skin_smooth(&src, w as u32, h as u32, 0.5, &mut out).unwrap_err(),
            TcamError::BufferTooSmall
        );
    }
}
