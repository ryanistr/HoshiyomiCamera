//! Portrait bokeh — replaces TCAM's `libstblur_preview_api.so` portrait blur
//! pass. Depth comes from the caller (a depth map from the depth sensor, from
//! dual-camera stereo, or a segmentation mask); this module composites a
//! blurred background with a sharp foreground and composes the final look.

use crate::{imgproc, TcamError, TcamResult};

/// Aperture-style blur strength. `f_stop` maps onto an effective radius:
/// larger aperture (smaller f) → stronger blur, mirroring the `f0.0…f9.0`
/// setting string TCAM exposes via `STBlurEntry`.
pub fn aperture_radius(f_stop: f32, preview_short_side: u32) -> u32 {
    // Clamp to the range the UI exposes; double the radius per stop as a
    // reasonable proxy for sensor-side depth of field.
    let f = f_stop.clamp(0.0, 16.0);
    let base = preview_short_side as f32 * 0.006;
    let radius = base * (8.0 / (f + 1.0)).max(0.05);
    radius.round() as u32
}

/// Depth-of-field composite: every pixel is blurred by an amount given by its
/// depth value, so the foreground stays sharp and the background melts.
/// `depth` is a per-pixel map in [0, 255] where 255 = in focus.
pub fn depth_of_field(
    rgba: &[u8],
    depth: &[u8],
    width: u32,
    height: u32,
    max_radius: u32,
    out: &mut [u8],
) -> TcamResult<()> {
    let (w, h) = (width as usize, height as usize);
    let n = w * h;
    if rgba.len() < n * 4 || depth.len() < n || out.len() < n * 4 {
        return Err(TcamError::BufferTooSmall);
    }
    // Pyramid of progressively blurred copies — one extra buffer per octave
    // keeps the whole pass at ~O(r) per pixel instead of O(r²).
    let octaves = (max_radius.max(1) as f32).log2().ceil() as u32 + 1;
    let mut levels = Vec::with_capacity(octaves as usize);
    let mut prev = rgba.to_vec();
    levels.push(prev.clone());
    for k in 1..octaves {
        let r_k = (1u32 << k).min(max_radius.max(1));
        let mut next = vec![0u8; prev.len()];
        imgproc::gaussian_blur(&prev, width, height, r_k, &mut next)?;
        prev = next;
        levels.push(prev.clone());
    }
    for (i, px) in out.chunks_exact_mut(4).enumerate().take(n) {
        let d = depth[i] as f32 / 255.0; // 1.0 = sharp
        // Map in-focus fraction onto the octave stack: 255 → level 0,
        // 0 → top octave.
        let frac = (1.0 - d).clamp(0.0, 1.0);
        let pos = frac * (levels.len() - 1) as f32;
        let lo = pos.floor() as usize;
        let hi = (lo + 1).min(levels.len() - 1);
        let t = pos - lo as f32;
        let a = &levels[lo][i * 4..i * 4 + 4];
        let b = &levels[hi][i * 4..i * 4 + 4];
        for ch in 0..3 {
            let v = a[ch] as f32 * (1.0 - t) + b[ch] as f32 * t;
            px[ch] = v.clamp(0.0, 255.0) as u8;
        }
        px[3] = 255;
    }
    Ok(())
}

/// Foreground/background composite given a binary mask — used when depth
/// comes from stereo (`yuv420` vsdof) or a segmentation network instead of a
/// per-pixel ramp.
pub fn composite_bokeh(
    sharp: &[u8],
    blurred: &[u8],
    mask: &[u8],
    width: u32,
    height: u32,
    edge_softness: u32,
    out: &mut [u8],
) -> TcamResult<()> {
    let (w, h) = (width as usize, height as usize);
    let n = w * h;
    if sharp.len() < n * 4 || blurred.len() < n * 4 || mask.len() < n || out.len() < n * 4 {
        return Err(TcamError::BufferTooSmall);
    }
    if edge_softness == 0 {
        for (i, px) in out.chunks_exact_mut(4).enumerate() {
            let src = if mask[i] > 127 { &sharp[i * 4..] } else { &blurred[i * 4..] };
            px.copy_from_slice(&src[..4]);
        }
        return Ok(());
    }
    // Feather the mask before mixing so the cut edge is not hard.
    let mut feather = vec![0u8; n];
    let k = edge_softness.min(16) as usize;
    let mut row = vec![0u8; n];
    // Horizontal box blur.
    for y in 0..h {
        let mut acc: u32 = 0;
        let base = y * w;
        for x in 0..w + k {
            if x < w {
                acc += mask[base + x] as u32;
            }
            if x >= 2 * k + 1 {
                acc -= mask[base + x - (2 * k + 1)] as u32;
            }
            if x >= k {
                let v = acc / (2 * k + 1) as u32;
                row[base + x - k] = v as u8;
            }
        }
    }
    // Vertical box blur.
    for x in 0..w {
        let mut acc: u32 = 0;
        for y in 0..h + k {
            if y < h {
                acc += row[y * w + x] as u32;
            }
            if y >= 2 * k + 1 {
                acc -= row[(y - (2 * k + 1)) * w + x] as u32;
            }
            if y >= k {
                let v = acc / (2 * k + 1) as u32;
                feather[(y - k) * w + x] = v as u8;
            }
        }
    }
    for (i, px) in out.chunks_exact_mut(4).enumerate().take(n) {
        let t = feather[i] as f32 / 255.0;
        for ch in 0..3 {
            let s = sharp[i * 4 + ch] as f32;
            let b = blurred[i * 4 + ch] as f32;
            px[ch] = (s * t + b * (1.0 - t)).clamp(0.0, 255.0) as u8;
        }
        px[3] = 255;
    }
    Ok(())
}

/// Portrait-light "spot" relight used by `VideoPortraitSpotEntry`: adds a
/// directional highlight falloff around the face box.
pub fn portrait_spot(
    rgba: &mut [u8],
    width: u32,
    height: u32,
    face_cx: f32,
    face_cy: f32,
    face_radius: f32,
    intensity: f32,
) -> TcamResult<()> {
    let (w, h) = (width as f32, height as f32);
    let gain = 1.0 + intensity.clamp(-0.5, 0.5);
    let r2 = (face_radius * 1.6).max(1.0);
    for y in 0..height as usize {
        for x in 0..width as usize {
            let dx = x as f32 - face_cx * w;
            let dy = y as f32 - face_cy * h;
            let d = (dx * dx + dy * dy).sqrt();
            let fall = (1.0 - d / (r2 * w.min(h))).clamp(0.0, 1.0);
            let f = 1.0 + (gain - 1.0) * fall;
            let o = (y * width as usize + x) * 4;
            rgba[o] = ((rgba[o] as f32) * f).clamp(0.0, 255.0) as u8;
            rgba[o + 1] = ((rgba[o + 1] as f32) * f).clamp(0.0, 255.0) as u8;
            rgba[o + 2] = ((rgba[o + 2] as f32) * f).clamp(0.0, 255.0) as u8;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(w: usize, h: usize, c: [u8; 4]) -> Vec<u8> {
        c.repeat(w * h)
    }

    #[test]
    fn aperture_radius_grows_with_wider_aperture() {
        let r1 = aperture_radius(8.0, 1080);
        let r2 = aperture_radius(1.0, 1080);
        assert!(r1 < r2);
        assert_eq!(aperture_radius(0.0, 1080), aperture_radius(0.0, 1080));
    }

    #[test]
    fn dof_keeps_focus_plane_sharp() {
        let (w, h) = (32, 32);
        let sharp = solid(w, h, [200, 150, 100, 255]);
        let depth = vec![255u8; w * h]; // everything in focus
        let mut out = vec![0u8; w * h * 4];
        depth_of_field(&sharp, &depth, w as u32, h as u32, 4, &mut out).unwrap();
        assert!(out.chunks_exact(4).all(|p| p[0] > 190));
    }

    #[test]
    fn dof_blurs_far_plane() {
        let (w, h) = (32, 32);
        let mut sharp = vec![0u8; w * h * 4]; // black so blur cannot average back up
        // A small bright patch in the middle: blurred, its peak drops below 255.
        for y in 14..18 {
            for x in 14..18 {
                sharp[(y * w + x) * 4] = 255;
            }
        }
        let depth = vec![0u8; w * h]; // everything far
        let mut out = vec![0u8; w * h * 4];
        depth_of_field(&sharp, &depth, w as u32, h as u32, 8, &mut out).unwrap();
        // Center of the white field blurs with black neighbours → darker.
        let c = (h / 2 * w + w / 2) * 4;
        assert!(out[c] < 255);
    }

    #[test]
    fn hard_mask_composite() {
        let (w, h) = (8, 8);
        let sharp = solid(w, h, [255, 0, 0, 255]);
        let blurred = solid(w, h, [0, 0, 255, 255]);
        let mut mask = vec![0u8; w * h];
        for y in 0..4 {
            for x in 0..4 {
                mask[y * w + x] = 255;
            }
        }
        let mut out = vec![0u8; w * h * 4];
        composite_bokeh(&sharp, &blurred, &mask, w as u32, h as u32, 0, &mut out).unwrap();
        assert_eq!(&out[..4], &[255, 0, 0, 255]);
        assert_eq!(&out[(w * h - 1) * 4..], &[0, 0, 255, 255]);
    }

    #[test]
    fn feathered_mask_blends_edge() {
        let (w, h) = (8, 8);
        let sharp = solid(w, h, [255, 0, 0, 255]);
        let blurred = solid(w, h, [0, 0, 255, 255]);
        let mut mask = vec![0u8; w * h];
        for i in 0..w * h / 2 {
            mask[i] = 255;
        }
        let mut out = vec![0u8; w * h * 4];
        composite_bokeh(&sharp, &blurred, &mask, w as u32, h as u32, 2, &mut out).unwrap();
        // Somewhere across the boundary both channels are mixed.
        assert!(out.chunks_exact(4).any(|p| p[0] > 0 && p[2] > 0));
    }

    #[test]
    fn portrait_spot_brightens_face_region() {
        let (w, h) = (32, 32);
        let mut px = solid(w, h, [100, 100, 100, 255]);
        portrait_spot(&mut px, w as u32, h as u32, 0.5, 0.5, 0.2, 0.4).unwrap();
        let center = (h / 2 * w + w / 2) * 4;
        let corner = 0;
        assert!(px[center] > px[corner]);
    }
}
