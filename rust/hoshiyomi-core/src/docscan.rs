//! Document scanning — replaces the document-mode role of the reference camera app's
//! `libXDocProcessSDK.so` (Youtu docprocess): edge detection, sheet
//! localisation, perspective rectification and background white-balancing.

use crate::HoshiyomiResult;

/// 2D point in pixel coordinates.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

/// A detected document quadrilateral (usually a slightly skewed rectangle).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Quad {
    pub tl: Point,
    pub tr: Point,
    pub br: Point,
    pub bl: Point,
}

/// Sobel gradient magnitude of a greyscale frame. Used to find the sheet edges
/// and to score perspective rectification quality.
pub fn sobel_magnitude(grey: &[u8], width: u32, height: u32, out: &mut [u8]) -> HoshiyomiResult<()> {
    let (w, h) = (width as usize, height as usize);
    if grey.len() < w * h || out.len() < w * h {
        return Err(crate::HoshiyomiError::BufferTooSmall);
    }
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let gx = (4 * grey[y * w + x + 1] as i32
                + 2 * grey[(y - 1) * w + x + 1] as i32
                + 2 * grey[(y + 1) * w + x + 1] as i32)
                - (4 * grey[y * w + x - 1] as i32
                    + 2 * grey[(y - 1) * w + x - 1] as i32
                    + 2 * grey[(y + 1) * w + x - 1] as i32);
            let gy = (4 * grey[(y + 1) * w + x] as i32
                + 2 * grey[(y + 1) * w + x + 1] as i32
                + 2 * grey[(y + 1) * w + x - 1] as i32)
                - (4 * grey[(y - 1) * w + x] as i32
                    + 2 * grey[(y - 1) * w + x + 1] as i32
                    + 2 * grey[(y - 1) * w + x - 1] as i32);
            let mag = ((gx * gx + gy * gy) as f32).sqrt();
            out[y * w + x] = mag.min(255.0) as u8;
        }
    }
    Ok(())
}

/// Detect the sheet: the biggest bright (paper-like) blob's bounding box,
/// padded and returned as a quad. Uses an Otsu-style global split — paper is
/// almost always the bright subject on a dark table.
pub fn detect_sheet(grey: &[u8], width: u32, height: u32) -> HoshiyomiResult<Quad> {
    let (w, h) = (width as usize, height as usize);
    if grey.len() < w * h {
        return Err(crate::HoshiyomiError::BufferTooSmall);
    }
    let thresh = otsu_threshold(grey);
    // Margin so the crop keeps the sheet border (documents lose content at a
    // tight fit and gain table at a loose one; ~1.5% each side).
    let mx = (w as f32 * 0.015) as usize;
    let my = (h as f32 * 0.015) as usize;
    let (mut x0, mut y0) = (w - 1, h - 1);
    let (mut x1, mut y1) = (0usize, 0usize);
    let mut hits = 0usize;
    for (i, &v) in grey.iter().enumerate() {
        if v as u32 <= thresh {
            continue;
        }
        hits += 1;
        let x = i % w;
        let y = i / w;
        if x < x0 { x0 = x; }
        if x > x1 { x1 = x; }
        if y < y0 { y0 = y; }
        if y > y1 { y1 = y; }
    }
    if hits < (w * h) / 16 {
        // No clear sheet: fall back to the full frame.
        let (x0, y0, x1, y1) = (0, 0, w - 1, h - 1);
        return Ok(quad_from(x0, y0, x1, y1, mx, my, w, h));
    }
    Ok(quad_from(x0, y0, x1, y1, mx, my, w, h))
}

fn quad_from(
    x0: usize, y0: usize, x1: usize, y1: usize,
    mx: usize, my: usize, w: usize, h: usize,
) -> Quad {
    let left = (x0.saturating_sub(mx)).min(w - 2);
    let top = (y0.saturating_sub(my)).min(h - 2);
    let right = (x1 + mx).min(w - 1).max(left + 1);
    let bottom = (y1 + my).min(h - 1).max(top + 1);
    Quad {
        tl: Point { x: left as f32, y: top as f32 },
        tr: Point { x: right as f32, y: top as f32 },
        br: Point { x: right as f32, y: bottom as f32 },
        bl: Point { x: left as f32, y: bottom as f32 },
    }
}

/// Otsu threshold: maximise between-class variance of the two bins.
pub fn otsu_threshold(grey: &[u8]) -> u32 {
    let mut hist = [0u32; 256];
    for &v in grey {
        hist[v as usize] += 1;
    }
    let total = grey.len() as u32;
    let mut sum = 0u64;
    for (i, &c) in hist.iter().enumerate() {
        sum += i as u64 * c as u64;
    }
    let mut best_var = 0u64;
    let mut best_t = 128u32;
    let mut sum_b = 0u64;
    let mut w_b = 0u32;
    for (i, &c) in hist.iter().enumerate() {
        w_b += c;
        if w_b == 0 { continue; }
        if w_b == total { break; }
        sum_b += i as u64 * c as u64;
        let w_f = total - w_b;
        let mean_b = sum_b / w_b as u64;
        let mean_f = (sum - sum_b) / w_f as u64;
        let diff = mean_b.saturating_sub(mean_f);
        let var = w_b as u64 * w_f as u64 * diff * diff;
        if var > best_var {
            best_var = var;
            best_t = i as u32;
        }
    }
    best_t
}

/**
 * Rectify the quad into an upright rectangle of [out_w]×[out_h] using
 * bilinear inverse mapping. Handles perspective implicitly because the quad
 * corners map onto the destination corners with per-pixel interpolation.
 */
pub fn rectify(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    bpp: u32,
    quad: Quad,
    out_w: u32,
    out_h: u32,
    out: &mut [u8],
) -> HoshiyomiResult<()> {
    let (sw, sh, dw, dh, bpp) = (
        src_w as usize, src_h as usize, out_w as usize, out_h as usize, bpp as usize,
    );
    if src.len() < sw * sh * bpp || out.len() < dw * dh * bpp {
        return Err(crate::HoshiyomiError::BufferTooSmall);
    }
    // Bilinear weights across the destination: interpolate between the four
    // corners as (u, v) blending (like the panorama bilinear, 2D version).
    for j in 0..dh {
        let v = j as f32 / (dh - 1).max(1) as f32;
        for i in 0..dw {
            let u = i as f32 / (dw - 1).max(1) as f32;
            // Bilinear corners: top edge, bottom edge, then vertical blend.
            let tx = quad.tl.x * (1.0 - u) + quad.tr.x * u;
            let ty = quad.tl.y * (1.0 - u) + quad.tr.y * u;
            let bx = quad.bl.x * (1.0 - u) + quad.br.x * u;
            let by = quad.bl.y * (1.0 - u) + quad.br.y * u;
            let px = tx * (1.0 - v) + bx * v;
            let py = ty * (1.0 - v) + by * v;
            // Clamp + sample with 2x2 bilinear for smoothness.
            let cx = px.clamp(0.0, (sw - 1) as f32);
            let cy = py.clamp(0.0, (sh - 1) as f32);
            let x0 = cx.floor() as usize;
            let y0 = cy.floor() as usize;
            let x1 = (x0 + 1).min(sw - 1);
            let y1 = (y0 + 1).min(sh - 1);
            let fx = cx - x0 as f32;
            let fy = cy - y0 as f32;
            let d = (j * dw + i) * bpp;
            for k in 0..bpp {
                let p00 = src[(y0 * sw + x0) * bpp + k] as f32;
                let p01 = src[(y0 * sw + x1) * bpp + k] as f32;
                let p10 = src[(y1 * sw + x0) * bpp + k] as f32;
                let p11 = src[(y1 * sw + x1) * bpp + k] as f32;
                let top = p00 + (p01 - p00) * fx;
                let bot = p10 + (p11 - p10) * fx;
                out[d + k] = (top + (bot - top) * fy) as u8;
            }
        }
    }
    Ok(())
}

/**
 * Document enhancement: whitens the paper background and deepens ink, like the
 * vendor's "magic Color" doc preset. Per-pixel: pull bright pixels toward
 * white, stretch mid-tones toward contrast.
 */
pub fn enhance_document(rgba: &mut [u8], white_point: u8) {
    let wp = white_point.max(64) as f32;
    for px in rgba.chunks_exact_mut(4) {
        for ch in 0..3 {
            let v = px[ch] as f32;
            // Normalise by the sheet white point, then gamma-stretch the ink.
            let n = (v / wp).clamp(0.0, 1.0);
            let stretched = if n > 0.72 {
                // Background: drive to white, preserving faint tints.
                255.0 * 0.85 + n * 38.0
            } else {
                // Ink: gamma-compress toward black for a deep, clean scan.
                255.0 * (n / 0.72).powf(2.5)
            };
            px[ch] = stretched.clamp(0.0, 255.0) as u8;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sheet_on_table(w: usize, h: usize) -> Vec<u8> {
        // Dark table with a bright tilted-ish sheet rectangle.
        let mut g = vec![40u8; w * h];
        for y in (h / 4)..(3 * h / 4) {
            for x in (w / 4)..(3 * w / 4) {
                g[y * w + x] = if y % 2 == 0 { 210 } else { 205 };
            }
        }
        g
    }

    #[test]
    fn sobel_finds_edges() {
        let (w, h) = (64, 48);
        let mut g = vec![10u8; w * h];
        for y in 0..h {
            for x in (w / 2)..w {
                g[y * w + x] = 200;
            }
        }
        let mut out = vec![0u8; w * h];
        sobel_magnitude(&g, w as u32, h as u32, &mut out).unwrap();
        // The vertical edge column must light up; the flat interior stays dim.
        assert!(out[w + w / 2] > 100);
        assert_eq!(out[2 * w + 5], 0);
        assert_eq!(out[2 * w + w - 5], 0);
    }

    #[test]
    fn otsu_splits_paper_from_table() {
        let g = sheet_on_table(96, 64);
        let t = otsu_threshold(&g);
        assert!(t > 41 && t < 204, "otsu picked {t}");
    }

    #[test]
    fn sheet_detection_covers_the_page() {
        let (w, h) = (96, 64);
        let g = sheet_on_table(w, h);
        let q = detect_sheet(&g, w as u32, h as u32).unwrap();
        // Bounding box is the sheet rectangle, padded.
        assert!(q.tl.x < 25.0);
        assert!(q.br.x > 70.0);
        assert!(q.tl.y < 17.0);
        assert!(q.bl.y > 46.0);
    }

    #[test]
    fn sheet_detection_falls_back_without_sheet() {
        let (w, h) = (48, 48);
        let g = vec![128u8; w * h];
        let q = detect_sheet(&g, w as u32, h as u32).unwrap();
        assert_eq!(q.tl, Point { x: 0.0, y: 0.0 });
        assert!(q.br.x >= (w - 2) as f32);
    }

    #[test]
    fn rectify_maps_quad_to_rectangle() {
        let (w, h) = (64, 48);
        let mut src = vec![0u8; w * h * 4];
        // Fill a 32x24 rectangle with solid colour inside a black frame.
        for y in 0..h {
            for x in 0..w {
                let inside = (12..44).contains(&x) && (12..36).contains(&y);
                let p = (y * w + x) * 4;
                if inside {
                    src[p] = 200; src[p + 1] = 100; src[p + 2] = 50; src[p + 3] = 255;
                } else {
                    src[p + 3] = 255;
                }
            }
        }
        let q = Quad {
            tl: Point { x: 12.0, y: 12.0 },
            tr: Point { x: 44.0, y: 12.0 },
            br: Point { x: 44.0, y: 36.0 },
            bl: Point { x: 12.0, y: 36.0 },
        };
        let mut out = vec![0u8; 32 * 24 * 4];
        rectify(&src, w as u32, h as u32, 4, q, 32, 24, &mut out).unwrap();

        // Interior stays the sheet colour; the far corner maps onto the quad's
        // excluded right edge (x = 44) and is the frame, as intended.
        let c = (12 * 32 + 16) * 4;
        assert!(out[c] > 150 && out[c + 1] > 50);
        assert!(out[0] > 150);
        let corner = (23 * 32 + 31) * 4;
        assert_eq!(out[corner], 0);
    }

    #[test]
    fn rectify_handles_skewed_quad() {
        let (w, h) = (80, 60);
        let mut src = vec![0u8; w * h * 4];
        for y in 0..h {
            for x in 0..w {
                let p = (y * w + x) * 4;
                src[p] = 255; src[p + 1] = 255; src[p + 2] = 255; src[p + 3] = 255;
            }
        }
        // Trapezoid (perspective skew): wider at the top.
        let q = Quad {
            tl: Point { x: 8.0, y: 8.0 },
            tr: Point { x: 72.0, y: 8.0 },
            br: Point { x: 56.0, y: 52.0 },
            bl: Point { x: 24.0, y: 52.0 },
        };
        let mut out = vec![0u8; 40 * 30 * 4];
        rectify(&src, w as u32, h as u32, 4, q, 40, 30, &mut out).unwrap();
        // Every sampled pixel comes from inside the trapezoid (all white).
        assert!(out.iter().step_by(4).all(|&v| v > 200));
    }

    #[test]
    fn enhance_document_whitens_and_inks() {
        let mut rgba = vec![0u8; 16];
        // Paper-ish light pixels and ink-dark pixels.
        rgba[0] = 200; rgba[1] = 205; rgba[2] = 198;
        rgba[8] = 60; rgba[9] = 55; rgba[10] = 58;
        enhance_document(&mut rgba, 205);
        assert!(rgba[0] > 250); // background whitened
        assert!(rgba[8] < 30); // ink deepened
    }

    #[test]
    fn rejects_undersized() {
        let g = vec![0u8; 100];
        let mut out = vec![0u8; 10];
        assert_eq!(
            sobel_magnitude(&g, 10, 10, &mut out).unwrap_err(),
            crate::HoshiyomiError::BufferTooSmall
        );
    }
}
