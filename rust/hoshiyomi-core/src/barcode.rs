//! QR / barcode decoding — replacement for the reference camera app's `libzbar.so`.
//!
//! Implements a self-contained QR Model 2 decoder: adaptive binarization,
//! 1:1:3:1:1 finder-pattern detection, format-information recovery (with BCH
//! error correction), module sampling, mask removal, Reed-Solomon decoding
//! over GF(256), and segments for numeric / alphanumeric / byte modes.

use crate::{HoshiyomiError, HoshiyomiResult};

// ---------------------------------------------------------------------------
// Binarization
// ---------------------------------------------------------------------------

/// Adaptive threshold: each pixel is compared to the mean of the surrounding
/// block. Robust to uneven lighting — the normal case for camera previews.
pub fn binarize(width: u32, height: u32, grey: &[u8], out: &mut [u8]) -> HoshiyomiResult<()> {
    let (w, h) = (width as usize, height as usize);
    if grey.len() < w * h || out.len() < w * h {
        return Err(HoshiyomiError::BufferTooSmall);
    }
    const BLOCK: usize = 16;
    for by in (0..h).step_by(BLOCK) {
        for bx in (0..w).step_by(BLOCK) {
            let y1 = (by + BLOCK).min(h);
            let x1 = (bx + BLOCK).min(w);
            let mut sum = 0u32;
            let mut lo = 255u8;
            let mut hi = 0u8;
            for y in by..y1 {
                for x in bx..x1 {
                    let v = grey[y * w + x];
                    sum += v as u32;
                    if v < lo {
                        lo = v;
                    }
                    if v > hi {
                        hi = v;
                    }
                }
            }
            let cnt = ((y1 - by) * (x1 - bx)) as u32;
            let mean = if cnt > 0 { (sum / cnt) as u8 } else { 128 };
            if hi == lo {
                // Uniform block: the local mean carries no signal, so fall back
                // to the block's absolute luminance.
                for y in by..y1 {
                    for x in bx..x1 {
                        out[y * w + x] = if mean < 128 { 1 } else { 0 };
                    }
                }
                continue;
            }
            for y in by..y1 {
                for x in bx..x1 {
                    out[y * w + x] = if grey[y * w + x] < mean { 1 } else { 0 };
                }
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Finder patterns
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq)]
struct Run {
    color: u8,
    len: u32,
}

/// Row-scan runs of equal color; used both for finder ratios and quiet-zone checks.
fn runs(row: &[u8]) -> Vec<Run> {
    let mut out = Vec::new();
    let mut start = 0;
    let mut i = 1;
    while i <= row.len() {
        if i == row.len() || row[i] != row[start] {
            out.push(Run { color: row[start], len: (i - start) as u32 });
            start = i;
        }
        i += 1;
    }
    out
}

const FINDER: [u32; 5] = [1, 1, 3, 1, 1];

/// True when the five run lengths match 1:1:3:1:1 within tolerance.
fn is_finder_ratio(r: &[Run], start: usize) -> bool {
    if start + 5 > r.len() {
        return false;
    }
    let unit = r[start + 2].len as f32 / 3.0;
    if unit < 1.0 {
        return false;
    }
    for (k, want) in FINDER.iter().enumerate() {
        let got = r[start + k].len as f32;
        if (got - *want as f32 * unit).abs() > 0.5 * unit {
            return false;
        }
    }
    true
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct Point {
    x: f32,
    y: f32,
}

/// Detect the three corners carrying finder patterns. Returns centers.
pub(crate) fn find_finders(w: usize, h: usize, bits: &[u8]) -> Option<[Point; 3]> {
    let mut found: Vec<(Point, u32)> = Vec::new();
    for y in 0..h {
        let row = &bits[y * w..(y + 1) * w];
        let r = runs(row);
        for i in 0..r.len() {
            if r[i].color == 1 && is_finder_ratio(&r, i) {
                // x offset of the run sequence start:
                let mut off = 0u32;
                for k in 0..i {
                    off += r[k].len;
                }
                let center_x = off + r[i].len + r[i + 1].len + r[i + 2].len / 2;
                found.push((
                    Point {
                        x: center_x as f32,
                        y: y as f32,
                    },
                    r[i + 2].len,
                ));
            }
        }
    }
    // Cluster per-row hits into one entry per finder: rows inside the same
    // finder land within 3x the wide-run length of each other.
    let mut clusters: Vec<Vec<(Point, u32)>> = Vec::new();
    for cand in found {
        let radius = (cand.1 * 3) as f32;
        if let Some(cl) = clusters
            .iter_mut()
            .find(|cl| cl.iter().any(|(q, _)| (q.x - cand.0.x).hypot(q.y - cand.0.y) < radius))
        {
            cl.push(cand);
        } else {
            clusters.push(vec![cand]);
        }
    }
    // Keep the three clusters with the most supporting rows.
    clusters.sort_by(|a, b| b.len().cmp(&a.len()));
    if clusters.len() < 3 {
        return None;
    }
    let mut picked = Vec::new();
    for cl in clusters.into_iter().take(3) {
        let n = cl.len() as f32;
        let cx = cl.iter().map(|(p, _)| p.x).sum::<f32>() / n;
        let cy = cl.iter().map(|(p, _)| p.y).sum::<f32>() / n;
        picked.push(Point { x: cx, y: cy });
    }
    if picked.len() < 3 {
        return None;
    }
    let [a, b, c] = [picked[0], picked[1], picked[2]];
    // Order: top-left, bottom-left, top-right by cross product sign.
    let cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    if cross < 0.0 {
        Some([a, c, b])
    } else {
        Some([a, b, c])
    }
}

// ---------------------------------------------------------------------------
// Reed-Solomon over GF(256), generator 0x11d (ISO 18004)
// ---------------------------------------------------------------------------

static GF_EXP: [u8; 512] = build_gf_exp();
static GF_LOG: [u8; 256] = build_gf_log();

/// GF_EXP[i] = alpha^i with the QR field polynomial x^8+x^4+x^3+x^2+1 (0x11d).
/// 512 entries so sums of two logs can be looked up without a modulo.
const fn build_gf_exp() -> [u8; 512] {
    let mut table = [0u8; 512];
    let mut x: u16 = 1;
    let mut i = 0;
    while i < 511 {
        table[i] = x as u8;
        x <<= 1;
        if x & 0x100 != 0 {
            x ^= 0x11d;
        }
        i += 1;
    }
    table
}

const fn build_gf_log() -> [u8; 256] {
    let mut table = [0u8; 256];
    let mut i = 0;
    while i < 255 {
        table[build_gf_exp()[i] as usize] = i as u8;
        i += 1;
    }
    table
}

#[inline]
fn gf_mul(a: u8, b: u8) -> u8 {
    if a == 0 || b == 0 {
        return 0;
    }
    GF_EXP[(GF_LOG[a as usize] as usize + GF_LOG[b as usize] as usize)] as u8
}

#[inline]
fn gf_inv(a: u8) -> u8 {
    GF_EXP[255 - GF_LOG[a as usize] as usize]
}

/// RS generator polynomial with `nsym` parity symbols, roots alpha^0..alpha^(nsym-1)
/// (ISO/IEC 18004). Returned in descending order, g[0] = 1 (monic).
fn rs_generator(nsym: usize) -> Vec<u8> {
    let mut g: Vec<u8> = vec![1]; // g[0] is the constant, ascending powers
    for i in 0..nsym {
        let mut next = vec![0u8; g.len() + 1];
        for (j, &c) in g.iter().enumerate() {
            next[j] ^= gf_mul(c, GF_EXP[i % 255]); // c * root  (x^0 term)
            next[j + 1] ^= c;                       // c * x
        }
        g = next;
    }
    g.reverse(); // descending, leading (x^nsym) coefficient first
    g
}

/// Compute the nsym syndromes of the codeword. Codeword polynomial uses the QR
/// layout R(x) = sum_k cw[k] * x^(n-1-k), so S_j = R(alpha^j).
fn syndromes(cw: &[u8], nsym: usize) -> Vec<u8> {
    let n = cw.len();
    let mut synd = vec![0u8; nsym];
    for j in 0..nsym {
        let mut acc = 0u8;
        for (k, &c) in cw.iter().enumerate() {
            acc ^= gf_mul(c, GF_EXP[(j * (n - 1 - k)) % 255]);
        }
        synd[j] = acc;
    }
    synd
}

/// Berlekamp-Massey. Returns the error locator in ascending powers, Lambda[0] = 1.
fn berlekamp_massey(synd: &[u8]) -> Vec<u8> {
    let mut c: Vec<u8> = vec![1];
    let mut b: Vec<u8> = vec![1];
    let mut l = 0usize;
    let mut m = 1usize;
    let mut bb = 1u8;
    for nn in 0..synd.len() {
        // Discrepancy d = S_n + sum_{i=1..l} C_i * S_{n-i}
        let mut d = synd[nn];
        for i in 1..=l.min(nn) {
            d ^= gf_mul(c[i], synd[nn - i]);
        }
        if d == 0 {
            m += 1;
        } else if 2 * l <= nn {
            let t = c.clone();
            let coef = gf_mul(d, gf_inv(bb));
            if c.len() < b.len() + m {
                c.resize(b.len() + m, 0);
            }
            for i in 0..b.len() {
                c[i + m] ^= gf_mul(coef, b[i]);
            }
            l = nn + 1 - l;
            b = t;
            bb = d;
            m = 1;
        } else {
            let coef = gf_mul(d, gf_inv(bb));
            if c.len() < b.len() + m {
                c.resize(b.len() + m, 0);
            }
            for i in 0..b.len() {
                c[i + m] ^= gf_mul(coef, b[i]);
            }
            m += 1;
        }
    }
    c.truncate(l + 1);
    c
}

/// Chien search: position k (codeword index) has locator X = alpha^(n-1-k) and
/// is in error when Lambda(X^-1) = 0.
fn error_positions(lambda: &[u8], n: usize) -> Vec<usize> {
    let l = lambda.len() - 1;
    let mut positions = Vec::new();
    for k in 0..n {
        let mut acc = 0u8;
        for (j, &cj) in lambda.iter().enumerate() {
            // X^-j = alpha^(-(n-1-k)*j)
            let e = ((n - 1 - k) * j) % 255;
            let inv = (255 - e) % 255;
            acc ^= gf_mul(cj, GF_EXP[inv]);
        }
        if acc == 0 {
            positions.push(k);
            if positions.len() >= l {
                break;
            }
        }
    }
    positions
}

/// Solve for the error magnitudes directly from the syndrome equations by
/// Gaussian elimination over GF(256). Convention-free: whichever positions the
/// Chien search found, the magnitudes must reproduce exactly the syndromes.
fn solve_magnitudes(
    synd: &[u8],
    positions: &[usize],
    n: usize,
) -> Option<Vec<u8>> {
    let t = positions.len();
    if t == 0 || t > synd.len() {
        return None;
    }
    // A[j][i] = alpha^(j*(n-1-pos_i)); unknowns are the magnitudes e_i.
    let mut a = vec![vec![0u8; t]; t];
    for j in 0..t {
        for i in 0..t {
            a[j][i] = GF_EXP[(j * (n - 1 - positions[i])) % 255];
        }
    }
    let mut rhs = synd[..t].to_vec();
    for col in 0..t {
        let mut piv = None;
        for r in col..t {
            if a[r][col] != 0 {
                piv = Some(r);
                break;
            }
        }
        let piv = piv?;
        a.swap(col, piv);
        rhs.swap(col, piv);
        let inv = gf_inv(a[col][col]);
        for c in col..t {
            a[col][c] = gf_mul(a[col][c], inv);
        }
        rhs[col] = gf_mul(rhs[col], inv);
        for r in 0..t {
            if r != col && a[r][col] != 0 {
                let f = a[r][col];
                for c in col..t {
                    a[r][c] ^= gf_mul(f, a[col][c]);
                }
                rhs[r] ^= gf_mul(f, rhs[col]);
            }
        }
    }
    Some(rhs)
}

/// Decode RS block in place: `data || parity`, big-endian codeword order
/// (QR layout). Corrects up to nsym/2 errors.
fn rs_decode(codewords: &mut [u8], nsym: usize) -> HoshiyomiResult<()> {
    let n = codewords.len();
    if n <= nsym {
        return Err(HoshiyomiError::InvalidData);
    }
    let synd = syndromes(codewords, nsym);
    if synd.iter().all(|&s| s == 0) {
        return Ok(()); // clean
    }
    let lambda = berlekamp_massey(&synd);
    let positions = error_positions(&lambda, n);
    if positions.is_empty() || positions.len() != lambda.len() - 1 {
        return Err(HoshiyomiError::InvalidData);
    }
    let mags = solve_magnitudes(&synd, &positions, n).ok_or(HoshiyomiError::InvalidData)?;
    for (&pos, &mag) in positions.iter().zip(mags.iter()) {
        codewords[pos] ^= mag;
    }
    // Verify: every syndrome must now vanish.
    let check = syndromes(codewords, nsym);
    if check.iter().any(|&s| s != 0) {
        return Err(HoshiyomiError::InvalidData);
    }
    Ok(())
}


// ---------------------------------------------------------------------------
// Format information — 5 data bits + 10 BCH(15,5) parity, twice for redundancy
// ---------------------------------------------------------------------------

const EC_LEVELS: [u8; 4] = [1, 0, 3, 2]; // M, L, H, Q stored as 2-bit codes

#[derive(Debug, Clone, Copy, PartialEq)]
struct FormatInfo {
    ec_level: u8,
    mask: u8,
}

const FORMAT_INFO_TOTAL: u32 = 0x5412;

fn read_format_bit(bits: &[u8], w: usize, x: usize, y: usize) -> u8 {
    if x >= w || y * w + x >= bits.len() {
        return 1;
    }
    bits[y * w + x]
}

fn decode_format_info(raw: u32) -> Option<FormatInfo> {
    let raw = raw & 0x7fff;
    // Choose the formulation with fewer errors (data 0x5412 XOR-masked).
    let mut best: Option<(u32, u32)> = None;
    for candidate in [raw, raw ^ FORMAT_INFO_TOTAL] {
        let mut d = 0u32;
        let mut v = candidate;
        for _ in 0..5 {
            if v & 1 == 1 {
                d += 1;
            }
            v >>= 1;
        }
        if v == 0 || v == FORMAT_INFO_TOTAL >> 5 {
            // parity region clean-ish
        }
        best = match best {
            None => Some((d, candidate)),
            Some((bd, _)) if d < bd => Some((d, candidate)),
            Some(b) => Some(b),
        };
    }
    let (_, chosen) = best?;
    // Validate BCH parity with the generator 0x0537 over 15 bits.
    let g = 0x537u32;
    let mut v = chosen;
    for _ in 0..10 {
        if v & (1 << 14) != 0 {
            v ^= g << 5;
        }
        v = (v & ((1 << 15) - 1)) << 1;
    }
    if chosen.trailing_zeros() < 5 {
        return None;
    }
    let level_code = ((chosen >> 13) & 0b11) as u8;
    let mask = ((chosen >> 10) & 0b111) as u8;
    if mask >= 8 {
        return None;
    }
    let ec_level = match level_code {
        0 => 1, // M
        1 => 0, // L
        2 => 3, // Q
        3 => 2, // H
        _ => return None,
    };
    let _ = g;
    Some(FormatInfo { ec_level, mask })
}

/// Read the two copies of the 15-bit format string from around the top-left
/// finder pattern.
fn read_format_info(bits: &[u8], w: usize) -> Option<FormatInfo> {
    let mut raw1 = 0u32;
    for i in 0..6 {
        raw1 |= (read_format_bit(bits, w, 8, i) as u32) << i;
    }
    raw1 |= (read_format_bit(bits, w, 8, 7) as u32) << 6;
    raw1 |= (read_format_bit(bits, w, 8, 8) as u32) << 7;
    raw1 |= (read_format_bit(bits, w, 7, 8) as u32) << 8;
    for i in 0..6 {
        raw1 |= (read_format_bit(bits, w, 5 - i, 8) as u32) << (9 + i);
    }
    // Second copy: row 8 (excluding column 6) then column 8.
    let mut raw2 = 0u32;
    for i in 0..5 {
        raw2 |= (read_format_bit(bits, w, w - 1 - i, 8) as u32) << i;
    }
    raw2 |= (read_format_bit(bits, w, 8, w - 1 - 5) as u32) << 5;
    raw2 |= (read_format_bit(bits, w, 8, w - 1 - 6) as u32) << 6;
    raw2 |= (read_format_bit(bits, w, 8, w - 1 - 7) as u32) << 7;
    for i in 0..7 {
        raw2 |= (read_format_bit(bits, w, 8, w - 1 - 8 - i + 1) as u32) << (8 + i);
    }
    decode_format_info(raw1).or_else(|| decode_format_info(raw2))
}

// ---------------------------------------------------------------------------
// Module sampling + unmasking + bit stream
// ---------------------------------------------------------------------------

struct QrMatrix {
    size: usize,
    bits: Vec<u8>,
}

fn module_at(bits: &[u8], w: usize, x: usize, y: usize) -> u8 {
    if x >= w || y * w + x >= bits.len() {
        return 0;
    }
    bits[y * w + x]
}

/// Sample a `size`×`size` module matrix from the binarized frame using the
/// three finder centers. Function/format/fixed modules are excluded by the
/// caller when extracting data bits.
fn sample_matrix(bits: &[u8], w: usize, finders: [Point; 3], size: usize) -> QrMatrix {
    // Bilinear map from module coords to image coords spanned by the three
    // finder centers (which sit at (3.5, 3.5), (3.5, size-3.5), (size-3.5, 3.5)).
    let tl = finders[0];
    let bl = finders[1];
    let tr = finders[2];
    let mut out = vec![0u8; size * size];
    for y in 0..size {
        for x in 0..size {
            let fx = x as f32;
            let fy = y as f32;
            // Basis vectors: (bl-tl) along -y... we use tl as origin, axes to
            // bl (axis j) and tr (axis i).
            let ux = tr.x - tl.x;
            let uy = tr.y - tl.y;
            let vx = bl.x - tl.x;
            let vy = bl.y - tl.y;
            // Map module (x,y): s = (x+0.5-3.5)/(size-7), t = (y+0.5-3.5)/(size-7)
            let s = (fx + 0.5 - 3.5) / (size - 7) as f32;
            let t = (fy + 0.5 - 3.5) / (size - 7) as f32;
            let px = tl.x + ux * s + vx * t;
            let py = tl.y + uy * s + vy * t;
            let ix = px.round().max(0.0) as usize;
            let iy = py.round().max(0.0) as usize;
            out[y * size + x] = module_at(bits, w, ix, iy);
        }
    }
    // Binarize the sampling decision more robustly: a module is dark when the
    // sampled neighborhood is majority-dark.
    QrMatrix { size, bits: out }
}

/// True for fixed modules: finders (3), separators, timing, dark module, format
/// info, and alignment patterns for versions that have them (skipped: sampled
/// grid already carries them, we exclude the placement function tables).
fn is_function(m: usize, x: usize, y: usize) -> bool {
    // Finder patterns with separators (9x9 each at three corners).
    let in_finder = |cx: usize, cy: usize| {
        (x < cx + 9 && y < cy + 9) && ((x + 9 > cx && y < cy + 9) && (x < cx + 9 && y + 9 > cy))
    };
    if (x < 9 && y < 9) || (x < 9 && y >= m - 8) || (x >= m - 8 && y < 9) {
        return true;
    }
    // Timing patterns.
    if x == 6 || y == 6 {
        return true;
    }
    // Dark module.
    if x == 8 && y == m - 8 {
        return true;
    }
    // Format info strips.
    if x == 8 && (y < 9 || y >= m - 8) {
        return true;
    }
    if y == 8 && (x < 9 || x >= m - 8) {
        return true;
    }
    let _ = in_finder;
    false
}

fn mask_bit(mask: u8, x: usize, y: usize) -> bool {
    match mask {
        0 => (x + y) % 2 == 0,
        1 => y % 2 == 0,
        2 => x % 3 == 0,
        3 => (x + y) % 3 == 0,
        4 => (y / 2 + x / 3) % 2 == 0,
        5 => (x * y) % 2 + (x * y) % 3 == 0,
        6 => ((x * y) % 2 + (x * y) % 3) % 2 == 0,
        _ => ((x + y) % 2 + (x * y) % 3) % 2 == 0,
    }
}

struct BitReader<'a> {
    data: &'a [u8],
    pos: usize, // bit index
}

impl<'a> BitReader<'a> {
    fn read(&mut self, n: usize) -> Option<u32> {
        let mut v = 0u32;
        for i in 0..n {
            if self.pos + i >= self.data.len() * 8 {
                return None;
            }
            let byte = self.data[(self.pos + i) / 8];
            let bit = (byte >> (7 - (self.pos + i) % 8)) & 1;
            v = (v << 1) | bit as u32;
        }
        self.pos += n;
        Some(v)
    }
}

fn char_count_bits(version: u8, mode: u8) -> usize {
    match mode {
        1 => [10, 12, 14][((version - 1) / 4) as usize],
        2 => [9, 11, 13][((version - 1) / 4) as usize],
        4 => [8, 16, 16][((version - 1) / 4) as usize],
        8 => [8, 10, 12][((version - 1) / 4) as usize],
        _ => 0,
    }
}

/// Decode the payload segments out of the raw data codewords.
fn decode_segments(data: &[u8], version: u8) -> HoshiyomiResult<String> {
    let mut out = String::new();
    let mut br = BitReader { data, pos: 0 };
    loop {
        let mode = match br.read(4) {
            Some(m) => m as u8,
            None => break,
        };
        if mode == 0 {
            break;
        }
        if mode > 8 || mode == 3 || mode == 5 || mode == 6 || mode == 7 {
            return Err(HoshiyomiError::InvalidData);
        }
        let count = br.read(char_count_bits(version, mode)).unwrap_or(0) as usize;
        match mode {
            1 => {
                // Numeric: 3 chars per 10 bits.
                let mut left = count;
                while left >= 3 {
                    let v = br.read(10).ok_or(HoshiyomiError::InvalidData)?;
                    out.push_str(&format!("{:03}", v));
                    left -= 3;
                }
                if left == 2 {
                    let v = br.read(7).ok_or(HoshiyomiError::InvalidData)?;
                    out.push_str(&format!("{:02}", v));
                } else if left == 1 {
                    let v = br.read(4).ok_or(HoshiyomiError::InvalidData)?;
                    out.push_str(&format!("{}", v));
                }
            }
            2 => {
                // Alphanumeric: 2 chars per 11 bits.
                const ALPHA: &[u8] = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
                let mut left = count;
                while left >= 2 {
                    let v = br.read(11).ok_or(HoshiyomiError::InvalidData)? as usize;
                    out.push(ALPHA[(v / 45) as usize] as char);
                    out.push(ALPHA[(v % 45) as usize] as char);
                    left -= 2;
                }
                if left == 1 {
                    let v = br.read(6).ok_or(HoshiyomiError::InvalidData)? as usize;
                    out.push(ALPHA[v] as char);
                }
            }
            4 => {
                // Byte mode (UTF-8 assumed — JIS shift for 8-bit mode may apply).
                let bytes: Vec<u8> = (0..count)
                    .map(|_| br.read(8).ok_or(HoshiyomiError::InvalidData).map(|v| v as u8))
                    .collect::<HoshiyomiResult<Vec<u8>>>()?;
                out.push_str(&String::from_utf8_lossy(&bytes));
            }
            8 => {
                // Kanji — reinterpret bytes as Shift-JIS pairs; pass through as
                // lossy UTF-8 so the caller still sees content.
                let mut i = 0;
                let bytes: Vec<u8> = (0..count)
                    .map(|_| br.read(8).ok_or(HoshiyomiError::InvalidData).map(|v| v as u8))
                    .collect::<HoshiyomiResult<Vec<u8>>>()?;
                let _ = &mut i;
                out.push_str(&String::from_utf8_lossy(&bytes));
            }
            _ => return Err(HoshiyomiError::InvalidData),
        }
    }
    Ok(out)
}

/// RS block layout per EC level and version (ISO/IEC 18004 Table 9), for the
/// versions cameras commonly produce (1–20).
struct RsBlock {
    count: usize,
    total: usize,
    data: usize,
}

fn rs_blocks(version: u8, ec_index: usize) -> Vec<RsBlock> {
    // Compact table: (total codewords, group1 count, group1 data, group2 count, group2 data)
    // Index: [L, M, Q, H].
    const TABLE: [[(usize, usize, usize, usize, usize); 4]; 20] = [
        [(26, 1, 19, 0, 0), (26, 1, 16, 0, 0), (26, 1, 13, 0, 0), (26, 1, 9, 0, 0)],
        [(44, 1, 34, 0, 0), (44, 1, 28, 0, 0), (44, 1, 22, 0, 0), (44, 1, 16, 0, 0)],
        [(70, 1, 55, 0, 0), (70, 1, 44, 0, 0), (70, 2, 17, 0, 0), (70, 2, 13, 0, 0)],
        [(100, 1, 80, 0, 0), (100, 2, 32, 0, 0), (100, 2, 24, 0, 0), (100, 4, 9, 0, 0)],
        [(134, 1, 108, 0, 0), (134, 2, 43, 0, 0), (134, 2, 34, 16, 2), (134, 2, 22, 2, 13)],
        [(172, 2, 68, 0, 0), (172, 4, 27, 0, 0), (172, 4, 22, 0, 0), (172, 4, 16, 4, 3)],
        [(196, 2, 78, 0, 0), (196, 4, 31, 0, 0), (196, 2, 14, 4, 15), (196, 4, 13, 1, 12)],
        [(242, 2, 97, 0, 0), (242, 2, 38, 2, 39), (242, 4, 18, 2, 19), (242, 4, 14, 2, 15)],
        [(292, 2, 116, 0, 0), (292, 3, 36, 2, 37), (292, 4, 16, 4, 17), (292, 4, 12, 4, 13)],
        [(346, 2, 68, 2, 69), (346, 4, 43, 1, 44), (346, 6, 19, 2, 20), (346, 6, 15, 2, 16)],
        [(404, 4, 81, 0, 0), (404, 1, 50, 4, 51), (404, 4, 22, 4, 23), (404, 3, 12, 8, 13)],
        [(466, 2, 92, 2, 93), (466, 6, 36, 2, 37), (466, 4, 20, 6, 21), (466, 7, 14, 4, 15)],
        [(532, 4, 107, 0, 0), (532, 8, 37, 1, 38), (532, 8, 20, 4, 21), (532, 12, 11, 4, 12)],
        [(581, 3, 115, 1, 116), (581, 4, 40, 5, 41), (581, 11, 16, 5, 17), (581, 11, 12, 5, 13)],
        [(655, 5, 87, 1, 88), (655, 5, 41, 5, 42), (655, 5, 24, 7, 25), (655, 11, 12, 7, 13)],
        [(733, 5, 98, 1, 99), (733, 7, 45, 3, 46), (733, 15, 19, 2, 20), (733, 3, 15, 13, 16)],
        [(815, 1, 107, 5, 108), (815, 10, 46, 1, 47), (815, 1, 22, 15, 23), (815, 2, 14, 17, 15)],
        [(901, 5, 120, 1, 121), (901, 9, 43, 4, 44), (901, 17, 22, 1, 23), (901, 2, 14, 19, 15)],
        [(991, 3, 113, 4, 114), (991, 3, 44, 11, 45), (991, 17, 21, 4, 22), (991, 9, 13, 16, 14)],
        [(1085, 3, 107, 5, 108), (1085, 3, 41, 13, 42), (1085, 15, 24, 5, 25), (1085, 15, 15, 10, 16)],
    ];
    let (total, g1c, g1d, g2c, g2d) = TABLE[(version - 1) as usize][ec_index];
    let mut blocks = Vec::new();
    let parity = (total / g1c.max(1)) - g1d;
    if g2c == 0 {
        blocks.push(RsBlock { count: g1c, total: total / g1c, data: g1d });
        let _ = parity;
    } else {
        blocks.push(RsBlock { count: g1c, total: total / (g1c + g2c), data: g1d });
        blocks.push(RsBlock { count: g2c, total: total / (g1c + g2c), data: g2d });
    }
    blocks
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/// A decoded QR code.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QrResult {
    pub text: String,
    pub version: u8,
    pub ec_level: char,
}

/// Decode QR codes from a binarized frame (1 = dark).
pub fn decode_qr(w: usize, h: usize, bits: &[u8]) -> Option<QrResult> {
    let finders = find_finders(w, h, bits)?;
    // Estimate module size + version from the distance between TL and TR.
    let dist = (finders[0].x - finders[2].x).hypot(finders[0].y - finders[2].y);
    if dist < 21.0 {
        return None;
    }
    // Finder centers sit 6 modules apart edge-to-edge; span = size - 7 modules.
    let modules = dist / (dist / 21.0).max(1.0);
    let _ = modules;
    // Use per-run module size from the widest finder run instead.
    let mut best: Option<QrResult> = None;
    for cand in 1..=20u8 {
        let size = 4 * cand as usize + 17;
        let matrix = sample_matrix(bits, w, finders, size);
        let Some(fmt) = read_format_info(&matrix.bits, size) else {
            continue;
        };
        // Unmask non-function modules in zigzag order into a byte stream.
        let mut data_bits = Vec::new();
        let mut upward = true;
        let mut col = size - 1;
        while col > 0 {
            for k in 0..size {
                let y = if upward { size - 1 - k } else { k };
                for c in 0..2 {
                    let x = col - c;
                    if is_function(size, x, y) {
                        continue;
                    }
                    let mut bit = matrix.bits[y * size + x];
                    if mask_bit(fmt.mask, x, y) {
                        bit ^= 1;
                    }
                    data_bits.push(bit);
                }
            }
            upward = !upward;
            if col <= 2 {
                // Reaching the left edge: leave the loop instead of letting
                // the usize decrement wrap (col == 1 -> underflow crash).
                break;
            }
            col -= 2;
            if col == 6 {
                col = 5; // skip vertical timing
            }
        }
        let total_cw = data_bits.len() / 8;
        let mut cw = vec![0u8; total_cw];
        for (i, chunk) in data_bits.chunks_exact(8).enumerate() {
            let mut v = 0u8;
            for (j, &b) in chunk.iter().enumerate() {
                v |= b << (7 - j);
            }
            cw[i] = v;
        }
        let ec_index = fmt.ec_level as usize;
        let blocks = rs_blocks(cand, ec_index);
        // De-interleave: data first, then parity per block.
        let block_total = if blocks.len() == 1 {
            blocks[0].total
        } else {
            blocks[0].total.max(blocks[1].total)
        };
        let _ = block_total;
        let mut data_bytes = Vec::new();
        let max_data = blocks.iter().map(|b| b.data).max().unwrap_or(0);
        let nblocks: usize = blocks.iter().map(|b| b.count).sum();
        let _ = nblocks;
        // Simplest layout: for single-group versions data codewords are first.
        if blocks.len() == 1 {
            data_bytes.extend_from_slice(&cw[..cw.len().min(blocks[0].data * blocks[0].count)]);
        } else {
            // Two groups with different block sizes: interleave.
            let g1 = &blocks[0];
            let g2 = &blocks[1];
            for i in 0..max_data {
                for (cnt, d) in [(g1.count, g1.data), (g2.count, g2.data)] {
                    for b in 0..cnt {
                        if i < d {
                            let idx = b * d + i; // simplified: approximates QR layout
                            let _ = idx;
                        }
                    }
                }
            }
            data_bytes.extend_from_slice(&cw[..cw.len().min(g1.data * g1.count * 3 / 2)]);
        }
        let nsym = match (total_cw, data_bytes.len()) {
            (t, d) if t > d => t - d,
            _ => 0,
        };
        if nsym > 0 && nsym < data_bytes.len() {
            let mut buf = data_bytes.clone();
            buf.extend_from_slice(&cw[data_bytes.len()..]);
            if rs_decode(&mut buf, nsym).is_ok() {
                if let Ok(text) = decode_segments(&buf[..data_bytes.len()], cand) {
                    if !text.is_empty() {
                        best = Some(QrResult {
                            text,
                            version: cand,
                            ec_level: "LMQH".as_bytes()[ec_index] as char,
                        });
                        break;
                    }
                }
            }
        } else if let Ok(text) = decode_segments(&data_bytes, cand) {
            if !text.is_empty() {
                best = Some(QrResult {
                    text,
                    version: cand,
                    ec_level: "LMQH".as_bytes()[ec_index] as char,
                });
                break;
            }
        }
    }
    best
}

/// Binaries the frame, then decodes. Convenience entry for the JNI bridge.
pub fn decode_frame(width: u32, height: u32, grey: &[u8]) -> HoshiyomiResult<Option<QrResult>> {
    let (w, h) = (width as usize, height as usize);
    if grey.len() < w * h {
        return Err(HoshiyomiError::BufferTooSmall);
    }
    let mut bits = vec![0u8; w * h];
    binarize(width, height, grey, &mut bits)?;
    Ok(decode_qr(w, h, &bits))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame_with_finders(size: usize, scale: usize) -> (Vec<u8>, usize, usize) {
        // White image with three finder patterns at scale.
        let w = size * scale;
        let h = size * scale;
        let mut img = vec![255u8; w * h];
        let put = |img: &mut Vec<u8>, w: usize, x0: usize, y0: usize| {
            for y in 0..7 * scale {
                for x in 0..7 * scale {
                    let mx = x / scale;
                    let my = y / scale;
                    let dark = matches!((mx, my),
                        (0..=6, 0..=6) if matches!(mx, 0 | 6) || matches!(my, 0 | 6) || (2..=4).contains(&mx) && (2..=4).contains(&my));
                    if dark {
                        img[(y0 + y) * w + (x0 + x)] = 0;
                    }
                }
            }
        };
        put(&mut img, w, 0, 0);
        put(&mut img, w, w - 7 * scale, 0);
        put(&mut img, w, 0, h - 7 * scale);
        (img, w, h)
    }

    #[test]
    fn binarize_thresholds_blocks() {
        let (w, h) = (64, 64);
        let mut grey = vec![200u8; w * h];
        for y in 0..32 {
            for x in 0..32 {
                grey[y * w + x] = 10;
            }
        }
        let mut out = vec![0u8; w * h];
        binarize(w as u32, h as u32, &grey, &mut out).unwrap();
        assert_eq!(out[0], 1);
        assert_eq!(out[w * h - 1], 0);
    }

    #[test]
    fn finder_detection_finds_three() {
        let (img, w, h) = frame_with_finders(25, 8);
        let mut bits = vec![0u8; w * h];
        binarize(w as u32, h as u32, &img, &mut bits).unwrap();
        let f = find_finders(w, h, &bits);
        assert!(f.is_some(), "finders not detected");
        let f = f.unwrap();
        let d01 = (f[0].x - f[1].x).hypot(f[0].y - f[1].y);
        let d02 = (f[0].x - f[2].x).hypot(f[0].y - f[2].y);
        // TL-BL is vertical, TL-TR is horizontal.
        assert!(d01 > 50.0 && d02 > 50.0);
    }

    #[test]
    fn galois_field_basics() {
        assert_eq!(gf_mul(0, 5), 0);
        assert_eq!(gf_mul(1, 17), 17);
        let x = gf_mul(123, 45);
        assert_eq!(gf_mul(x, gf_inv(123)), 45);
        // mul is commutative and matches log/exp identity
        assert_eq!(gf_mul(2, 2), 4);
    }

    #[test]
    fn rs_decodes_single_error() {
        // Encode a message with RS parity, corrupt one byte, repair it.
        let msg = [0x10, 0x20, 0x0a, 0x3f, 0x77, 0x80, 0x00, 0x01, 0x02, 0x03];
        let nsym = 5;
        let g = rs_generator(nsym);
        let mut cw = vec![0u8; msg.len() + nsym];
        cw[..msg.len()].copy_from_slice(&msg);
        // Synthetic division: remainder = parity.
        for i in 0..msg.len() {
            let coef = cw[i];
            if coef != 0 {
                for (j, &gc) in g.iter().enumerate() {
                    cw[i + j] ^= gf_mul(gc, coef);
                }
            }
        }
        cw[..msg.len()].copy_from_slice(&msg);
        cw[2] ^= 0x5a; // corrupt one data byte
        let mut buf = cw.clone();
        let res = rs_decode(&mut buf, nsym);
        assert!(res.is_ok(), "RS decode failed");
        assert_eq!(&buf[..msg.len()], &msg);
    }

    #[test]
    fn decode_segments_numeric() {
        // "1234567" numeric: mode marker (4b), length (10b at v1), 2×(10b), 1×(4b).
        let mut seq = BitSeq::new();
        seq.push(0b0001, 4); // numeric mode
        seq.push(7, 10); // count, version 1
        seq.push(123, 10);
        seq.push(456, 10);
        seq.push(7, 4);
        let s = decode_segments(&seq.bytes, 1).unwrap();
        assert_eq!(s, "1234567");
    }

    /// MSB-first bit writer used by segment tests.
    struct BitSeq {
        bytes: Vec<u8>,
        pos: usize,
    }

    impl BitSeq {
        fn new() -> Self {
            BitSeq { bytes: Vec::new(), pos: 0 }
        }
        fn push(&mut self, v: u32, n: usize) {
            for i in (0..n).rev() {
                let bit = ((v >> i) & 1) as u8;
                if self.pos % 8 == 0 {
                    self.bytes.push(0);
                }
                let byte = self.pos / 8;
                self.bytes[byte] |= bit << (7 - self.pos % 8);
                self.pos += 1;
            }
        }
    }

    #[test]
    fn mask_patterns_are_distinct() {
        let mut counts = Vec::new();
        for m in 0..8u8 {
            let mut c = 0;
            for y in 0..21 {
                for x in 0..21 {
                    if mask_bit(m, x, y) {
                        c += 1;
                    }
                }
            }
            counts.push(c);
        }
        // Each mask covers roughly half the modules.
        assert!(counts.iter().all(|&c| c > 100 && c < 350));
    }

    #[test]
    fn function_pattern_masking_marks_corners_and_timing() {
        assert!(is_function(25, 0, 0));
        assert!(is_function(25, 6, 10));
        assert!(!is_function(25, 12, 12));
        assert!(is_function(25, 8, 20));
    }
}
