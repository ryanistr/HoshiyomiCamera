//! Image processing pipeline — replaces the reference camera app's `libArcFilter.so` filter passes
//! and the color/tone half of `libeffect.so`. Operates directly on RGBA pixels.

use crate::HoshiyomiResult;

/// 256-entry tone curve (identity-free). Used by filters and beauty ops.
pub type Curve = [u8; 256];

pub fn identity_curve() -> Curve {
    let mut c = [0u8; 256];
    for (i, v) in c.iter_mut().enumerate() {
        *v = i as u8;
    }
    c
}

/// Apply a tone curve to each RGB channel.
pub fn apply_curve(rgba: &mut [u8], curve: &Curve) -> HoshiyomiResult<()> {
    for px in rgba.chunks_exact_mut(4) {
        px[0] = curve[px[0] as usize];
        px[1] = curve[px[1] as usize];
        px[2] = curve[px[2] as usize];
    }
    Ok(())
}

/// Brightness in [-1.0, 1.0]; contrast centered at 0.5.
pub fn brightness_contrast(rgba: &mut [u8], brightness: f32, contrast: f32) -> HoshiyomiResult<()> {
    let b = (brightness * 255.0) as i32;
    let c = 1.0 + contrast;
    for px in rgba.chunks_exact_mut(4) {
        for ch in 0..3 {
            let v = px[ch] as f32 / 255.0;
            let v = ((v - 0.5) * c + 0.5).clamp(0.0, 1.0);
            let v = (v * 255.0) as i32 + b;
            px[ch] = v.clamp(0, 255) as u8;
        }
    }
    Ok(())
}

/// Saturation multiplier (1.0 = identity) and warmth in [-1.0, 1.0].
pub fn saturation_warmth(rgba: &mut [u8], saturation: f32, warmth: f32) -> HoshiyomiResult<()> {
    let w = (warmth * 30.0) as i32;
    for px in rgba.chunks_exact_mut(4) {
        let r = px[0] as f32;
        let g = px[1] as f32;
        let b = px[2] as f32;
        let grey = 0.299 * r + 0.587 * g + 0.114 * b;
        let r = grey + (r - grey) * saturation;
        let g = grey + (g - grey) * saturation;
        let b = grey + (b - grey) * saturation;
        let r = r + w as f32;
        let b = b - w as f32;
        px[0] = r.clamp(0.0, 255.0) as u8;
        px[1] = g.clamp(0.0, 255.0) as u8;
        px[2] = b.clamp(0.0, 255.0) as u8;
    }
    Ok(())
}

/// Separable Gaussian blur, radius in pixels. Used for bokeh background and
/// skin smoothing.
pub fn gaussian_blur(
    rgba: &[u8],
    width: u32,
    height: u32,
    radius: u32,
    out: &mut [u8],
) -> HoshiyomiResult<()> {
    let (w, h) = (width as usize, height as usize);
    let r = radius as usize;
    if rgba.len() < w * h * 4 || out.len() < w * h * 4 || w * h == 0 {
        return Err(crate::HoshiyomiError::BufferTooSmall);
    }
    if r == 0 {
        out.copy_from_slice(rgba);
        return Ok(());
    }
    let sigma = (r as f32 / 2.0).max(0.6);
    let mut kernel = Vec::with_capacity(2 * r + 1);
    let two_sigma_sq = 2.0 * sigma * sigma;
    let mut sum = 0.0;
    for i in 0..=(2 * r) {
        let d = i as f32 - r as f32;
        let v = (-d * d / two_sigma_sq).exp();
        kernel.push(v);
        sum += v;
    }
    for k in kernel.iter_mut() {
        *k /= sum;
    }
    // Fixpoint kernel to avoid per-pixel float math.
    let kfix: Vec<i32> = kernel.iter().map(|v| (v * 4096.0) as i32).collect();

    let mut tmp = vec![0i32; w * h * 4];
    // Horizontal pass: 3 RGB channels only; alpha passes through.
    for y in 0..h {
        let row_in = y * w * 4;
        for x in 0..w {
            let mut acc = [0i32; 3];
            for (i, k) in kfix.iter().enumerate() {
                let sx = if i >= r {
                    (x + i - r).min(w - 1)
                } else {
                    x.saturating_sub(r - i)
                };
                let o = row_in + sx * 4;
                acc[0] += *k * rgba[o] as i32;
                acc[1] += *k * rgba[o + 1] as i32;
                acc[2] += *k * rgba[o + 2] as i32;
            }
            let d = y * w * 4 + x * 4;
            tmp[d] = (acc[0] + 2048) >> 12;
            tmp[d + 1] = (acc[1] + 2048) >> 12;
            tmp[d + 2] = (acc[2] + 2048) >> 12;
            tmp[d + 3] = rgba[row_in + x * 4 + 3] as i32;
        }
    }
    // Vertical pass into out.
    for y in 0..h {
        for x in 0..w {
            let mut acc = [0i32; 3];
            for (i, k) in kfix.iter().enumerate() {
                let sy = if i >= r {
                    (y + i - r).min(h - 1)
                } else {
                    y.saturating_sub(r - i)
                };
                let o = sy * w * 4 + x * 4;
                acc[0] += *k * tmp[o];
                acc[1] += *k * tmp[o + 1];
                acc[2] += *k * tmp[o + 2];
            }
            let d = y * w * 4 + x * 4;
            out[d] = ((acc[0] + 2048) >> 12).clamp(0, 255) as u8;
            out[d + 1] = ((acc[1] + 2048) >> 12).clamp(0, 255) as u8;
            out[d + 2] = ((acc[2] + 2048) >> 12).clamp(0, 255) as u8;
            out[d + 3] = tmp[d + 3].clamp(0, 255) as u8;
        }
    }
    Ok(())
}

/// Vignette: darkens corners with a smooth radial falloff. `strength` in
/// [0.0, 1.0].
pub fn vignette(rgba: &mut [u8], width: u32, height: u32, strength: f32) -> HoshiyomiResult<()> {
    let (w, h) = (width as f32, height as f32);
    let cx = w / 2.0;
    let cy = h / 2.0;
    let max_d = (cx * cx + cy * cy).sqrt();
    for y in 0..height as usize {
        for x in 0..width as usize {
            let dx = x as f32 - cx;
            let dy = y as f32 - cy;
            let d = (dx * dx + dy * dy).sqrt() / max_d;
            let f = 1.0 - (d * d).clamp(0.0, 1.0) * strength;
            let o = (y * width as usize + x) * 4;
            rgba[o] = ((rgba[o] as f32) * f).clamp(0.0, 255.0) as u8;
            rgba[o + 1] = ((rgba[o + 1] as f32) * f).clamp(0.0, 255.0) as u8;
            rgba[o + 2] = ((rgba[o + 2] as f32) * f).clamp(0.0, 255.0) as u8;
        }
    }
    Ok(())
}

/// Unsharp mask (the classic phone-camera "defined detail" filter).
pub fn sharpen(
    rgba: &[u8],
    width: u32,
    height: u32,
    amount: f32,
    out: &mut [u8],
) -> HoshiyomiResult<()> {
    if rgba.len() < width as usize * height as usize * 4
        || out.len() < width as usize * height as usize * 4
    {
        return Err(crate::HoshiyomiError::BufferTooSmall);
    }
    // Sharpen = original + amount * (original - blurred).
    let mut tmp = vec![0u8; rgba.len()];
    gaussian_blur(rgba, width, height, 2, &mut tmp)?;
    for (o, (src, blur)) in out.chunks_exact_mut(4).zip(rgba.chunks_exact(4).zip(tmp.chunks_exact(4)))
    {
        for ch in 0..3 {
            let s = src[ch] as i32;
            let b = blur[ch] as i32;
            let v = s as f32 + amount * (s - b) as f32;
            o[ch] = v.clamp(0.0, 255.0) as u8;
        }
        o[3] = src[3];
    }
    Ok(())
}

/**
 * Multi-frame stacking for night mode — the Stargazer counterpart of the
 * original's `libNvStreamingSdkCore` / MTK MFNR night capture: average N
 * frames (shot noise scales as 1/sqrt(N)), then lift the shadows with a
 * sqrt-ish curve so detail survives without blowing highlights.
 */
pub fn mean_stack(
    frames: &[&[u8]],
    width: u32,
    height: u32,
    bpp: u32,
    out: &mut [u8],
) -> HoshiyomiResult<()> {
    let (w, h, bpp) = (width as usize, height as usize, bpp as usize);
    if frames.is_empty() || out.len() < w * h * bpp {
        return Err(crate::HoshiyomiError::BufferTooSmall);
    }
    for f in frames {
        if f.len() < w * h * bpp {
            return Err(crate::HoshiyomiError::BufferTooSmall);
        }
    }
    let n = frames.len() as u32;
    let mut acc = vec![0u32; w * h * bpp];
    for f in frames {
        for (a, &v) in acc.iter_mut().zip(f.iter()) {
            *a += v as u32;
        }
    }
    for (o, a) in out.iter_mut().zip(acc.iter()) {
        *o = (*a / n) as u8;
    }
    Ok(())
}

/**
 * Night shadow lift: applies a sqrt-gamma to the low end only, leaving the
 * highlights alone — the "night view" tone the vendor's SuperNight produces
 * after stacking. [shadowPoint] is the luminance below which lifting applies.
 */
pub fn night_lift(rgba: &mut [u8], shadow_point: u8, strength: f32) {
    let sp = shadow_point as f32;
    let t = strength.clamp(0.0, 1.0);
    for px in rgba.chunks_exact_mut(4) {
        let luma = 0.299 * px[0] as f32 + 0.587 * px[1] as f32 + 0.114 * px[2] as f32;
        if luma >= sp {
            continue;
        }
        // Lift factor falls off toward the shadow point.
        let ratio = (sp - luma) / sp.max(1.0);
        let lift = (ratio * ratio).sqrt() * t * 90.0;
        for ch in 0..3 {
            let v = px[ch] as f32 + lift * (1.0 - px[ch] as f32 / 255.0);
            px[ch] = v.clamp(0.0, 255.0) as u8;
        }
    }
}

/// 3×3 per-channel matrix (RGB→RGB), matching the `ImageStyleEntry` /
/// `ArcFilter` color-grading matrices.
pub fn color_matrix(rgba: &mut [u8], m: &[f32; 9]) -> HoshiyomiResult<()> {
    for px in rgba.chunks_exact_mut(4) {
        let r = px[0] as f32;
        let g = px[1] as f32;
        let b = px[2] as f32;
        let nr = m[0] * r + m[1] * g + m[2] * b;
        let ng = m[3] * r + m[4] * g + m[5] * b;
        let nb = m[6] * r + m[7] * g + m[8] * b;
        px[0] = nr.clamp(0.0, 255.0) as u8;
        px[1] = ng.clamp(0.0, 255.0) as u8;
        px[2] = nb.clamp(0.0, 255.0) as u8;
    }
    Ok(())
}

/// Predefined style matrices mirroring the reference camera app's filter catalog
/// (`TranssionFilterEntry` / `ArcFilterEntry`).
pub mod styles {
    use super::Curve;

    pub fn none() -> [f32; 9] {
        [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    }
    /// "Cool blue" — lifts blues, drops reds.
    pub fn cool() -> [f32; 9] {
        [0.9, 0.0, 0.05, 0.0, 0.95, 0.05, 0.0, 0.0, 1.1]
    }
    /// "Warm" — sunny tint.
    pub fn warm() -> [f32; 9] {
        [1.1, 0.0, 0.0, 0.0, 1.0, 0.0, -0.05, 0.0, 0.9]
    }
    /// "Mono" — BT.601 luma.
    pub fn mono() -> [f32; 9] {
        [0.299, 0.587, 0.114, 0.299, 0.587, 0.114, 0.299, 0.587, 0.114]
    }
    /// Retro film look: desaturated, lifted shadows, warm cast.
    pub fn vintage() -> [f32; 9] {
        [0.9, 0.1, 0.1, 0.05, 0.85, 0.05, 0.0, 0.05, 0.8]
    }
    /// Faded/V2 style — pulls whites down.
    pub fn faded_curve() -> Curve {
        let mut c = [0u8; 256];
        for (i, v) in c.iter_mut().enumerate() {
            let t = i as f32 / 255.0;
            *v = (255.0 * (0.92 * t + 0.04)) as u8;
        }
        c
    }
    /// High-contrast curve used by "SuperNight" post.
    pub fn contrast_curve() -> Curve {
        let mut c = [0u8; 256];
        for (i, v) in c.iter_mut().enumerate() {
            let t = i as f32 / 255.0;
            let s = (t - 0.5) * 1.25 + 0.5;
            *v = (255.0 * s.clamp(0.0, 1.0)) as u8;
        }
        c
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(c: [u8; 4], n: usize) -> Vec<u8> {
        c.repeat(n)
    }

    #[test]
    fn brightness_and_contrast_extremes() {
        let mut px = solid([128, 128, 128, 255], 4);
        brightness_contrast(&mut px, 0.5, 0.0).unwrap();
        assert_eq!(px[0], 255);
        let mut px = solid([200, 200, 200, 255], 4);
        brightness_contrast(&mut px, -1.0, 0.0).unwrap();
        assert_eq!(px[0], 0);
    }

    #[test]
    fn saturation_grey_is_neutral() {
        let mut px = solid([100, 100, 100, 255], 4);
        saturation_warmth(&mut px, 2.0, 0.0).unwrap();
        assert_eq!(&px[..3], &[100, 100, 100]);
    }

    #[test]
    fn warmth_shifts_toward_red() {
        let mut px = solid([128, 128, 128, 255], 4);
        saturation_warmth(&mut px, 1.0, 1.0).unwrap();
        assert!(px[0] > 128);
        assert!(px[2] < 128);
    }

    #[test]
    fn blur_preserves_alpha_and_averages() {
        let (w, h) = (16, 8);
        let mut src = vec![0u8; w * h * 4];
        for i in 0..w * h {
            src[i * 4] = 100;
            src[i * 4 + 3] = 255;
        }
        let mut out = vec![0u8; w * h * 4];
        gaussian_blur(&src, w as u32, h as u32, 3, &mut out).unwrap();
        // Interior pixels average back to roughly the constant.
        let o = (h / 2 * w + w / 2) * 4;
        assert!((out[o] as i32 - 100).abs() <= 5);
        assert_eq!(out[o + 3], 255);
    }

    #[test]
    fn sharpen_on_flat_image_is_identity() {
        let (w, h) = (12, 12);
        let src = solid([90, 120, 60, 255], w * h);
        let mut out = vec![0u8; src.len()];
        sharpen(&src, w as u32, h as u32, 0.8, &mut out).unwrap();
        for (a, b) in src.chunks_exact(4).zip(out.chunks_exact(4)) {
            assert_eq!(&a[..3], &b[..3]);
        }
    }

    #[test]
    fn vignette_darkens_only_corners() {
        let (w, h) = (64, 64);
        let mut px = solid([200, 200, 200, 255], w * h);
        vignette(&mut px, w as u32, h as u32, 0.8).unwrap();
        let center = (h / 2 * w + w / 2) * 4;
        let corner = 3 * 4;
        assert!(px[center] > px[corner]);
    }

    #[test]
    fn mean_stack_averages_frames() {
        let (w, h) = (16, 16);
        let a: Vec<u8> = (0..w * h * 4).map(|i| (i % 200) as u8).collect();
        let b: Vec<u8> = (0..w * h * 4).map(|i| ((i + 100) % 200) as u8).collect();
        let c: Vec<u8> = (0..w * h * 4).map(|i| ((i + 200) % 200) as u8).collect();
        let mut out = vec![0u8; w * h * 4];
        mean_stack(&[&a, &b, &c], w as u32, h as u32, 4, &mut out).unwrap();
        // Each output byte equals the integer mean of the three inputs.
        for i in 0..w * h * 4 {
            let expect = (a[i] as u32 + b[i] as u32 + c[i] as u32) / 3;
            assert_eq!(out[i], expect as u8);
        }
    }

    #[test]
    fn mean_stack_reduces_random_noise() {
        let (w, h) = (32, 32);
        let base = vec![60u8; w * h * 4];
        // Frames fluctuating ±20 around 60: the mean approaches 60.
        let frames: Vec<Vec<u8>> = (0..8).map(|k| {
            base.iter().enumerate().map(|(i, &_v)| {
                ((60 + ((i * 7 + k * 13) % 41) as i32 - 20)) as u8
            }).collect()
        }).collect();
        let refs: Vec<&[u8]> = frames.iter().map(|f| f.as_slice()).collect();
        let mut out = vec![0u8; w * h * 4];
        mean_stack(&refs, w as u32, h as u32, 4, &mut out).unwrap();
        let mean: f32 = out.iter().map(|&v| v as f32).sum::<f32>() / out.len() as f32;
        assert!((mean - 60.0).abs() < 3.0);
    }

    #[test]
    fn night_lift_brightens_shadows_only() {
        let mut rgba = vec![0u8; 16];
        rgba[0] = 20; rgba[1] = 20; rgba[2] = 20; // shadow pixel
        rgba[8] = 220; rgba[9] = 220; rgba[10] = 220; // highlight pixel
        night_lift(&mut rgba, 120, 0.8);
        assert!(rgba[0] > 30, "shadow lifted: {}", rgba[0]);
        assert_eq!(rgba[8..=10], [220, 220, 220]);
    }

    #[test]
    fn mean_stack_rejects_mismatched_frame() {
        let (w, h) = (8, 8);
        let a = vec![10u8; w * h * 4];
        let b = vec![10u8; 10];
        let mut out = vec![0u8; w * h * 4];
        assert_eq!(
            mean_stack(&[&a, &b], w as u32, h as u32, 4, &mut out).unwrap_err(),
            crate::HoshiyomiError::BufferTooSmall
        );
    }

    #[test]
    fn color_matrix_mono_collapses_channels() {
        let mut px = solid([200, 30, 120, 255], 4);
        color_matrix(&mut px, &styles::mono()).unwrap();
        let want = 0.299 * 200.0 + 0.587 * 30.0 + 0.114 * 120.0;
        assert_eq!(px[0], want as u8);
        assert_eq!(px[1], px[0]);
        assert_eq!(px[2], px[0]);
    }

    #[test]
    fn curves_are_monotonic() {
        fn mono(c: &Curve) -> bool {
            c.windows(2).all(|w| w[0] <= w[1])
        }
        assert!(mono(&styles::faded_curve()));
        assert!(mono(&styles::contrast_curve()));
        assert!(mono(&identity_curve()));
    }

    #[test]
    fn apply_curve_identity_is_noop() {
        let mut px = solid([11, 22, 33, 44], 4);
        apply_curve(&mut px, &identity_curve()).unwrap();
        assert_eq!(&px[..4], &[11, 22, 33, 44]);
    }
}
