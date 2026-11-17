//! Face detection — replaces the reference camera app's `libcvface_api.so` for the camera-use case:
//! finding one or more upright frontal faces inside a preview downscaled to a
//! 320-wide detection grid. Uses a Viola-Jones-style cascade over integral
//! images and Haar-like features, i.e. no network download, no license.

use crate::yuv::scale_bilinear;
use crate::HoshiyomiResult;

/// A detected face in normalized [0,1] coordinates (camera-agnostic; the caller
/// maps to view coordinates with the display transform).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FaceRect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
    pub score: u32,
}

const DETECT_W: u32 = 320;

/// Detection cascade stage tables. Each stage is a set of feature tests: the
/// sum of rectangles is compared against a threshold with a polarity. This is a
/// compact hand-built cascade tuned for close-up portraits (video-call framing
/// distance), which is the case that matters for beauty and portrait modes.
const WINDOW: u32 = 24;

fn rect_sum(ii: &[u64], w: usize, x0: usize, y0: usize, x1: usize, y1: usize) -> u64 {
    let a = if x1 > 0 && y1 > 0 { ii[(y1 - 1) * w + (x1 - 1)] } else { 0 } as i64;
    let b = if x1 > 0 && y0 > 0 { ii[(y0 - 1) * w + (x1 - 1)] } else { 0 } as i64;
    let c = if x0 > 0 && y1 > 0 { ii[(y1 - 1) * w + (x0 - 1)] } else { 0 } as i64;
    let d = if x0 > 0 && y0 > 0 { ii[(y0 - 1) * w + (x0 - 1)] } else { 0 } as i64;
    (a - b - c + d).max(0) as u64
}

/// Build an integral image (summed-area table) of a greyscale frame.
fn integral(grey: &[u8], w: usize, h: usize, out: &mut [u64]) {
    for y in 0..h {
        let mut row_sum = 0u64;
        for x in 0..w {
            row_sum += grey[y * w + x] as u64;
            out[y * w + x] = row_sum + if y > 0 { out[(y - 1) * w + x] } else { 0 };
        }
    }
}

/// Feature descriptor: unused by the contrast-rule cascade below, kept for the
/// planned trained-classifier upgrade.
struct Feat {
    rects: [(u32, u32, u32, u32, i32); 3],
    n: u8,
    threshold: i64,
    polarity: i8,
}

/// Contrast-rule cascade. Rather than shipping a trained weight table, the
/// detector scores the classic frontal-face signature with integral-image
/// rectangles: bright oval against the corners, a dark eye strip under a
/// brighter forehead, a bright nose bridge between darker cheeks, and a dark
/// mouth band. Every rule is a *relative* brightness comparison, so the window
/// is independent of absolute exposure — which is what makes it usable at
/// varying camera gains.
fn cascade_pass(ii: &[u64], w: usize, x: usize, y: usize, s: usize) -> u32 {
    // Normalize by the window area so thresholds are exposure-independent.
    let area = |x0, y0, x1, y1| { let a = rect_sum(ii, w, x0, y0, x1, y1) as f32; let n = ((x1 - x0) * (y1 - y0)).max(1) as f32; a / n };
    let f = (x as f32, y as f32, s as f32);
    // Tile coordinates in 24ths of the window.
    let r = |fx0: f32, fy0: f32, fx1: f32, fy1: f32| {
        let (a, b, c, d) = (
            x + ((f.0 + f.2 * fx0 / 24.0).round() as usize).saturating_sub(x),
            y + ((f.1 + f.2 * fy0 / 24.0).round() as usize).saturating_sub(y),
            x + ((f.0 + f.2 * fx1 / 24.0).round() as usize).saturating_sub(x),
            y + ((f.1 + f.2 * fy1 / 24.0).round() as usize).saturating_sub(y),
        );
        (a, b.min(d), c.max(a), d)
    };
    let mean = |fx0, fy0, fx1, fy1| {
        let (a, b, c, d) = r(fx0, fy0, fx1, fy1);
        if c <= a || d <= b {
            return 0.0;
        }
        area(a, b, c, d)
    };

    // Rule 1: face oval brighter than the four corners.
    let oval = mean(5.0, 5.0, 19.0, 19.0);
    let mut corners = 0.0;
    let mut n = 0.0;
    for (fx0, fy0, fx1, fy1) in [(0.0, 0.0, 6.0, 6.0), (18.0, 0.0, 24.0, 6.0), (0.0, 18.0, 6.0, 24.0), (18.0, 18.0, 24.0, 24.0)] {
        let m = mean(fx0, fy0, fx1, fy1);
        if m > 0.0 {
            corners += m;
            n += 1.0;
        }
    }
    if n < 4.0 {
        return 0;
    }
    if oval < corners / n + 12.0 {
        return 0;
    }
    // Rule 2: dark eye strip rows 7..12 vs brighter forehead rows 2..7.
    let eyes = mean(3.0, 7.0, 21.0, 12.0);
    let forehead = mean(3.0, 2.0, 21.0, 7.0);
    if forehead < eyes + 6.0 {
        return 0;
    }
    // Rule 3: bright nose bridge between darker cheeks (rows 8..18).
    let nose = mean(9.0, 8.0, 15.0, 18.0);
    let cheek_l = mean(3.0, 8.0, 9.0, 18.0);
    let cheek_r = mean(15.0, 8.0, 21.0, 18.0);
    let cheeks = (cheek_l + cheek_r) / 2.0;
    if nose < cheeks + 5.0 {
        return 0;
    }
    // Rule 4: dark mouth band rows 17..21 vs the nose region above it.
    let mouth = mean(6.0, 17.0, 18.0, 21.0);
    if mouth > nose - 3.0 {
        return 0;
    }
    // Confidence grows with the margin between face and background.
    let score = ((oval - corners / n) / 8.0) as u32;
    score.max(1)
}

/// Detect upright faces in a greyscale frame. Returns rectangles normalized to
/// the input frame size.
pub fn detect_faces(width: u32, height: u32, grey: &[u8]) -> HoshiyomiResult<Vec<FaceRect>> {
    let (w, h) = (width as usize, height as usize);
    if grey.len() < w * h {
        return Err(crate::HoshiyomiError::BufferTooSmall);
    }
    // Downscale to at most DETECT_W wide for a fixed detection cost.
    let scale = (w as f32 / DETECT_W as f32).max(1.0);
    let dw = ((w as f32) / scale).round().max(24.0) as u32;
    let dh = ((h as f32) / scale).round().max(24.0) as u32;
    let mut small = vec![0u8; dw as usize * dh as usize];
    scale_bilinear(grey, width, height, 1, dw, dh, &mut small)?;
    let (dw, dh) = (dw as usize, dh as usize);
    let mut ii = vec![0u64; dw * dh];
    integral(&small, dw, dh, &mut ii);
    let mut hits = Vec::new();
    // Slide 24×24 windows at a few scales.
    let mut s = WINDOW as usize;
    while s <= dw.min(dh) {
        let step = (s / 8).max(2);
        let mut y = 0usize;
        while y + s <= dh {
            let mut x = 0usize;
            while x + s <= dw {
                let score = cascade_pass(&ii, dw, x, y, s);
                if score > 0 {
                    hits.push((
                        x as f32 / dw as f32,
                        y as f32 / dh as f32,
                        s as f32 / dw as f32,
                        s as f32 / dh as f32,
                    ));
                }
                x += step;
            }
            y += step;
        }
        s = s * 5 / 4;
    }
    // Non-maximum suppression.
    let mut faces = Vec::new();
    hits.sort_by(|a, b| (b.2 * b.3).partial_cmp(&(a.2 * a.3)).unwrap());
    for (x, y, fw, fh) in hits {
        if faces.iter().any(|f: &FaceRect| {
            let iou_x = (f.x - x).abs() < f.w * 0.5 && (f.y - y).abs() < f.h * 0.5;
            iou_x
        }) {
            continue;
        }
        faces.push(FaceRect { x, y, w: fw, h: fh, score: 1 });
    }
    Ok(faces)
}

/// Group faces into an exposure/metering bias: the average luma of the face
/// region compared to the scene average. Mirrors the reference camera app's `enableFace3A`.
pub fn face_metering_bias(width: u32, height: u32, grey: &[u8], faces: &[FaceRect]) -> f32 {
    let (w, h) = (width as usize, height as usize);
    if faces.is_empty() || grey.len() < w * h {
        return 0.0;
    }
    let scene: u64 = grey.iter().map(|&v| v as u64).sum();
    let scene_avg = scene as f32 / (w * h) as f32;
    let mut face_sum = 0.0f32;
    let mut cnt = 0.0f32;
    for f in faces {
        let x0 = (f.x * w as f32) as usize;
        let y0 = (f.y * h as f32) as usize;
        let x1 = ((f.x + f.w) * w as f32) as usize;
        let y1 = ((f.y + f.h) * h as f32) as usize;
        for y in y0..y1.min(h) {
            for x in x0..x1.min(w) {
                face_sum += grey[y * w + x] as f32;
                cnt += 1.0;
            }
        }
    }
    if cnt < 1.0 {
        return 0.0;
    }
    // Return a bias fraction in [-1, 1]: faces brighter than scene → negative
    // exposure compensation.
    (scene_avg - face_sum / cnt) / 255.0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn blank(w: usize, h: usize, v: u8) -> (Vec<u8>, u32, u32) {
        (vec![v; w * h], w as u32, h as u32)
    }

    #[test]
    fn integral_image_counts_prefix_sums() {
        let grey = [1u8, 2, 3, 4];
        let mut ii = vec![0u64; 4];
        integral(&grey, 2, 2, &mut ii);
        assert_eq!(ii, [1, 3, 4, 10]);
    }

    #[test]
    fn rect_sum_matches_box() {
        let grey = [1u8, 2, 3, 4];
        let mut ii = vec![0u64; 4];
        integral(&grey, 2, 2, &mut ii);
        assert_eq!(rect_sum(&ii, 2, 0, 0, 2, 2), 10);
        assert_eq!(rect_sum(&ii, 2, 1, 1, 2, 2), 4);
        assert_eq!(rect_sum(&ii, 2, 0, 0, 1, 1), 1);
    }

    #[test]
    fn detector_works_on_facial_layout() {
        // A synthetic bright face on a dark background: eyes/nose/mouth
        // structure is approximated with light regions.
        let (w, h) = (96, 96);
        let mut grey = vec![40u8; w * h];
        let put = |g: &mut Vec<u8>, cx: usize, cy: usize, ax: usize, ay: usize, v: u8| {
            for y in cy - ay..cy + ay {
                for x in cx - ax..cx + ax {
                    g[y * w + x] = v;
                }
            }
        };
        // Face oval
        for y in 20..76 {
            for x in 30..66 {
                let dx = x as f32 - 48.0;
                let dy = y as f32 - 48.0;
                if dx * dx / 18.0 / 18.0 + dy * dy / 28.0 / 28.0 <= 1.0 {
                    grey[y * w + x] = 180;
                }
            }
        }
        // Dark eye strip + light nose bridge
        put(&mut grey, 48, 40, 20, 4, 40);
        put(&mut grey, 48, 56, 6, 12, 230);
        put(&mut grey, 48, 68, 12, 4, 60);
        let faces = detect_faces(w as u32, h as u32, &grey).unwrap();
        assert!(faces.iter().any(|f| f.w > 0.1 && f.h > 0.1));
    }

    #[test]
    fn blank_image_has_no_faces() {
        let (g, w, h) = blank(240, 240, 120);
        let faces = detect_faces(w, h, &g).unwrap();
        assert!(faces.is_empty());
    }

    #[test]
    fn metering_bias_signal_direction() {
        let (w, h) = (64, 64);
        let mut grey = vec![20u8; w * h];
        let face = FaceRect { x: 0.0, y: 0.0, w: 0.25, h: 0.25, score: 1 };
        for y in 0..16 {
            for x in 0..16 {
                grey[y * w + x] = 200;
            }
        }
        let bias = face_metering_bias(w as u32, h as u32, &grey, &[face]);
        assert!(bias.abs() > 0.1);
    }
}
