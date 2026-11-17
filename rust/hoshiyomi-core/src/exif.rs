//! EXIF read/write — replaces the 1,586-LOC `ExifInterface` with a zero-copy
//! Rust parser. Camera JPEGs arrive as `byte[]`; this module reads GPS, orientation,
//! timestamps and maker tags from those bytes without instantiating a Bitmap.

use crate::{HoshiyomiError, HoshiyomiResult};

/// TIFF byte order tag.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Endian {
    Little,
    Big,
}

impl Endian {
    #[inline]
    fn u16(self, b: &[u8]) -> u16 {
        match self {
            Endian::Little => u16::from_le_bytes([b[0], b[1]]),
            Endian::Big => u16::from_be_bytes([b[0], b[1]]),
        }
    }
    #[inline]
    fn u32(self, b: &[u8]) -> u32 {
        match self {
            Endian::Little => u32::from_le_bytes([b[0], b[1], b[2], b[3]]),
            Endian::Big => u32::from_be_bytes([b[0], b[1], b[2], b[3]]),
        }
    }
}

/// EXIF tag ids we care about. Extending this list is how new metadata gets added.
pub mod tags {
    pub const ORIENTATION: u16 = 0x0112;
    pub const MAKE: u16 = 0x010f;
    pub const MODEL: u16 = 0x0110;
    pub const DATE_TIME: u16 = 0x0132;
    pub const EXIF_IFD: u16 = 0x8769;
    pub const GPS_IFD: u16 = 0x8825;
    pub const PIX_X: u16 = 0x011a;
    pub const PIX_Y: u16 = 0x011b;
    pub const ISO: u16 = 0x8827;
    pub const EXPOSURE_TIME: u16 = 0x829a;
    pub const F_NUMBER: u16 = 0x829d;
    pub const FOCAL_LENGTH: u16 = 0x920a;
    pub const DATE_TIME_ORIG: u16 = 0x9003;
    pub const DATE_TIME_DIGITIZED: u16 = 0x9004;
    pub const GPS_LATITUDE_REF: u16 = 0x0001;
    pub const GPS_LATITUDE: u16 = 0x0002;
    pub const GPS_LONGITUDE_REF: u16 = 0x0003;
    pub const GPS_LONGITUDE: u16 = 0x0004;
    pub const GPS_ALTITUDE_REF: u16 = 0x0005;
    pub const GPS_ALTITUDE: u16 = 0x0006;
    pub const GPS_TIMESTAMP: u16 = 0x0007;
    pub const GPS_DATESTAMP: u16 = 0x001d;
}

/// A parsed EXIF entry.
#[derive(Debug, Clone, PartialEq)]
pub struct ExifEntry {
    pub tag: u16,
    pub kind: u16,
    pub count: u32,
    /// Value read inline from the 4-byte value slot (numerics only).
    pub value: Option<u32>,
    /// Rational numerator+denominator for FNUMBER / FOCAL / GPS coords.
    pub rational: Option<(u32, u32)>,
    /// String payload (ASCII) for MAKE / MODEL / timestamps.
    pub string: Option<String>,
}

impl ExifEntry {
    pub fn orientation_degrees(&self) -> Option<u32> {
        if self.tag != tags::ORIENTATION {
            return None;
        }
        self.value.map(|v| match v {
            3 | 4 => 180,
            5 | 8 => 270,
            6 | 7 => 90,
            _ => 0,
        })
    }
}

const MAX_ENTRIES: usize = 256;

/// Parse the EXIF segment of a JPEG. Accepts the raw JPEG bytes and locates the
/// APP1/Exif block; passing an already-extracted TIFF blob also works because the
/// parser scans for the `II`/`MM` magic when the JPEG signature is absent.
pub fn parse(data: &[u8]) -> HoshiyomiResult<Vec<ExifEntry>> {
    let body = locate_exif(data).ok_or(HoshiyomiError::InvalidData)?;
    let body = &data[body..];
    if body.len() < 8 {
        return Err(HoshiyomiError::InvalidData);
    }
    let endian = match &body[..2] {
        b"II" => Endian::Little,
        b"MM" => Endian::Big,
        _ => return Err(HoshiyomiError::InvalidData),
    };
    let magic = endian.u16(&body[2..4]);
    if magic != 0x2a {
        return Err(HoshiyomiError::InvalidData);
    }
    let ifd0_off = endian.u32(&body[4..8]) as usize;
    let mut out = Vec::new();
    read_ifd(body, endian, ifd0_off, &mut out, 0)?;
    Ok(out)
}

fn locate_exif(data: &[u8]) -> Option<usize> {
    if data.len() < 4 {
        return None;
    }
    // Raw TIFF passed directly.
    if data.starts_with(b"II") || data.starts_with(b"MM") {
        return Some(0);
    }
    if data[0] != 0xff || data[1] != 0xd8 {
        return None;
    }
    let mut i = 2usize;
    while i + 4 <= data.len() {
        if data[i] != 0xff {
            i += 2;
            continue;
        }
        let marker = data[i + 1];
        if marker == 0xda {
            return None; // SOS, no EXIF found before image data
        }
        let len = u16::from_be_bytes([data[i + 2], data[i + 3]]) as usize;
        if i + 2 + len > data.len() {
            return None;
        }
        if marker == 0xe1 && len > 8 && &data[i + 4..i + 10] == b"Exif\x00\x00" {
            return Some(i + 10);
        }
        i += 2 + len;
    }
    None
}

fn read_ifd(
    body: &[u8],
    endian: Endian,
    offset: usize,
    out: &mut Vec<ExifEntry>,
    depth: usize,
) -> HoshiyomiResult<()> {
    if depth > 4 || offset + 2 > body.len() {
        return Err(HoshiyomiError::InvalidData);
    }
    let count = endian.u16(&body[offset..offset + 2]) as usize;
    if count > MAX_ENTRIES || offset + 2 + count * 12 + 4 > body.len() {
        return Err(HoshiyomiError::InvalidData);
    }
    let mut exif_ifd_off = None;
    let mut gps_ifd_off = None;
    for n in 0..count {
        let e = offset + 2 + n * 12;
        let tag = endian.u16(&body[e..e + 2]);
        let kind = endian.u16(&body[e + 2..e + 4]);
        let cnt = endian.u32(&body[e + 4..e + 8]);
        let slot = &body[e + 8..e + 12];

        let mut item = ExifEntry {
            tag,
            kind,
            count: cnt,
            value: None,
            rational: None,
            string: None,
        };
        let inline_size = type_size(kind) as usize * cnt as usize;
        if inline_size <= 4 {
            item.value = Some(match kind {
                1 | 6 | 7 => endian.u32(&[slot[0], 0, 0, 0]),
                3 => endian.u16(&[slot[0], slot[1]]) as u32,
                _ => endian.u32(slot),
            });
        } else {
            let data_off = endian.u32(slot) as usize;
            if data_off + inline_size <= body.len() {
                match kind {
                    2 => {
                        // ASCII
                        let end = body[data_off..].iter().position(|&c| c == 0).unwrap_or(cnt as usize);
                        item.string = String::from_utf8_lossy(
                            &body[data_off..data_off + end.min(cnt as usize)],
                        )
                        .trim_end_matches('\0')
                        .to_string()
                        .into();
                    }
                    5 => {
                        // RATIONAL
                        if data_off + 8 <= body.len() {
                            item.rational = Some((
                                endian.u32(&body[data_off..data_off + 4]),
                                endian.u32(&body[data_off + 4..data_off + 8]),
                            ));
                        }
                    }
                    _ => {}
                }
            }
        }
        if tag == tags::EXIF_IFD {
            exif_ifd_off = item.value.map(|v| v as usize);
        } else if tag == tags::GPS_IFD {
            gps_ifd_off = item.value.map(|v| v as usize);
        } else {
            out.push(item);
        }
    }
    if let Some(off) = exif_ifd_off {
        read_ifd(body, endian, off, out, depth + 1)?;
    }
    if let Some(off) = gps_ifd_off {
        read_ifd(body, endian, off, out, depth + 1)?;
    }
    Ok(())
}

const fn type_size(kind: u16) -> u32 {
    match kind {
        1 | 2 | 6 | 7 => 1, // BYTE, ASCII, SBYTE, UNDEFINED
        3 | 8 => 2,        // SHORT, SSHORT
        4 | 9 => 4,        // LONG, SLONG
        5 | 10 => 8,       // RATIONAL, SRATIONAL
        11 | 12 => 4,      // FLOAT, DOUBLE is 8 but rare
        _ => 1,
    }
}

// ---------------------------------------------------------------------------
// Writer — builds an APP1/Exif segment we can prepend to an encoder's output.
// ---------------------------------------------------------------------------

/// A tag/value pair queued for writing.
#[derive(Debug, Clone)]
pub struct WriteTag {
    pub tag: u16,
    pub kind: u16,
    pub payload: Vec<u8>,
}

impl WriteTag {
    pub fn ascii(tag: u16, s: &str) -> Self {
        let mut p = s.as_bytes().to_vec();
        p.push(0);
        WriteTag { tag, kind: 2, payload: p }
    }
    pub fn short(tag: u16, v: u16) -> Self {
        WriteTag { tag, kind: 3, payload: v.to_be_bytes().to_vec() }
    }
    pub fn long(tag: u16, v: u32) -> Self {
        WriteTag { tag, kind: 4, payload: v.to_be_bytes().to_vec() }
    }
    pub fn rational(tag: u16, num: u32, den: u32) -> Self {
        let mut p = Vec::with_capacity(8);
        p.extend_from_slice(&num.to_be_bytes());
        p.extend_from_slice(&den.to_be_bytes());
        WriteTag { tag, kind: 5, payload: p }
    }
    /// GPS coordinate as three rationals (d, m, s).
    pub fn gps_coord(tag: u16, deg: u32, min: u32, sec_centisec: u32) -> Self {
        let mut p = Vec::with_capacity(24);
        for (n, d) in [(deg, 1u32), (min, 1u32), (sec_centisec, 100u32)] {
            p.extend_from_slice(&n.to_be_bytes());
            p.extend_from_slice(&d.to_be_bytes());
        }
        WriteTag { tag, kind: 5, payload: p }
    }
}

/// Serialize tags as a complete APP1 (Exif) JPEG segment, ready to insert after
/// the SOI marker.
pub fn build_app1(tags: &[WriteTag]) -> HoshiyomiResult<Vec<u8>> {
    // Layout: II*\0 + IFD0 offset(4) + IFD0 + next-IFD(4=0) + payloads.
    let header = 8usize;
    let ifd_size = 2 + tags.len() * 12 + 4;
    let mut data_off = header + ifd_size;
    let mut segment = Vec::with_capacity(data_off + tags.len() * 24 + 8);
    segment.extend_from_slice(b"MM");
    segment.extend_from_slice(&0x2au16.to_be_bytes());
    segment.extend_from_slice(&8u32.to_be_bytes()); // IFD0 at offset 8
    segment.extend_from_slice(&(tags.len() as u16).to_be_bytes());
    let mut sorted: Vec<&WriteTag> = tags.iter().collect();
    sorted.sort_by_key(|t| t.tag);
    for t in &sorted {
        segment.extend_from_slice(&t.tag.to_be_bytes());
        segment.extend_from_slice(&t.kind.to_be_bytes());
        let units = match t.kind {
            3 => 2,
            4 | 11 => 4,
            5 | 10 => 8,
            _ => 1,
        };
        let count = if units == 0 {
            0
        } else {
            t.payload.len() / units
        };
        segment.extend_from_slice(&(count as u32).to_be_bytes());
        if t.payload.len() <= 4 {
            segment.extend_from_slice(&t.payload);
            for _ in t.payload.len()..4 {
                segment.push(0);
            }
        } else {
            segment.extend_from_slice(&(data_off as u32).to_be_bytes());
            data_off += t.payload.len();
        }
    }
    segment.extend_from_slice(&0u32.to_be_bytes()); // no next IFD
    for t in &sorted {
        if t.payload.len() > 4 {
            segment.extend_from_slice(&t.payload);
        }
    }
    let mut out = Vec::with_capacity(segment.len() + 8);
    out.extend_from_slice(&[0xff, 0xe1]);
    // Length covers the two length bytes + "Exif\0\0" + the TIFF segment.
    out.extend_from_slice(&((segment.len() + 8) as u16).to_be_bytes());
    out.extend_from_slice(b"Exif\x00\x00");
    out.extend_from_slice(&segment);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn synthetic(app1: &[u8]) -> Vec<u8> {
        let mut jpeg = vec![0xff, 0xd8];
        jpeg.extend_from_slice(app1);
        jpeg.extend_from_slice(&[0xff, 0xd9]);
        jpeg
    }

    #[test]
    fn builds_and_parses_ascii_and_short() {
        let tags = vec![
            WriteTag::ascii(tags::MAKE, "ProjectTCAM"),
            WriteTag::ascii(tags::MODEL, "X6739"),
            WriteTag::short(tags::ORIENTATION, 6),
        ];
        let app1 = build_app1(&tags).unwrap();
        let jpeg = synthetic(&app1);
        let entries = parse(&jpeg).unwrap();
        let by_tag = |t: u16| entries.iter().find(|e| e.tag == t).unwrap();
        assert_eq!(by_tag(tags::MAKE).string.as_deref(), Some("ProjectTCAM"));
        assert_eq!(by_tag(tags::MODEL).string.as_deref(), Some("X6739"));
        assert_eq!(by_tag(tags::ORIENTATION).value, Some(6));
        assert_eq!(by_tag(tags::ORIENTATION).orientation_degrees(), Some(90));
    }

    #[test]
    fn app1_length_field_matches_payload() {
        let tags = vec![WriteTag::short(tags::ORIENTATION, 6)];
        let app1 = build_app1(&tags).unwrap();
        let len = u16::from_be_bytes([app1[2], app1[3]]) as usize;
        // Everything after the 2 length bytes is counted, including the six
        // bytes of "Exif\0\0" — the JPEG APP1 header convention that real
        // decoders (BitmapFactory) skip by.
        assert_eq!(len, app1.len() - 2);
        // The marker (FF E1) isn't counted in the length field: marker + len
        // must equal the whole APP1 segment.
        assert_eq!(2 + len, app1.len());
    }

    #[test]
    fn roundtrips_offsets() {
        for (raw, want) in [(1u16, 0), (3, 180), (6, 90), (8, 270)] {
            let app1 = build_app1(&[WriteTag::short(tags::ORIENTATION, raw)]).unwrap();
            let entries = parse(&synthetic(&app1)).unwrap();
            assert_eq!(
                entries.iter().find(|e| e.tag == tags::ORIENTATION).unwrap().orientation_degrees(),
                Some(want)
            );
        }
    }

    #[test]
    fn rational_gps_roundtrip() {
        let app1 = build_app1(&[
            WriteTag::ascii(tags::GPS_LATITUDE_REF, "N"),
            WriteTag::gps_coord(tags::GPS_LATITUDE, 12, 34, 5600),
        ])
        .unwrap();
        let entries = parse(&synthetic(&app1)).unwrap();
        let lat = entries.iter().find(|e| e.tag == tags::GPS_LATITUDE).unwrap();
        assert_eq!(lat.rational, Some((12, 1)));
        let app1 = build_app1(&[WriteTag::rational(tags::FOCAL_LENGTH, 50, 1)]).unwrap();
        let entries = parse(&synthetic(&app1)).unwrap();
        assert_eq!(
            entries.iter().find(|e| e.tag == tags::FOCAL_LENGTH).unwrap().rational,
            Some((50, 1))
        );
    }

    #[test]
    fn rejects_non_jpeg() {
        let junk = vec![0u8; 64];
        assert_eq!(parse(&junk).unwrap_err(), HoshiyomiError::InvalidData);
    }

    #[test]
    fn rejects_after_sos() {
        let mut jpeg = vec![0xff, 0xd8, 0xff, 0xda];
        jpeg.extend_from_slice(&[0u8; 16]);
        assert!(parse(&jpeg).is_err());
    }
}
