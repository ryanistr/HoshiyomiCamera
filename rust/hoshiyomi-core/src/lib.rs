//! Stargazer Camera (HoshiyomiCam) native compute core.
//!
//! Replaces the prebuilt libraries shipped inside the reference camera APK:
//! - [`yuv`]      -> libyuvutil.so
//! - [`exif`]     -> the app's 1,586-LOC ExifInterface
//! - [`barcode`]  -> libzbar.so
//! - [`bokeh`]    -> libstblur_preview_api.so
//! - [`stitch`]   -> libpanorama.so / libwideselfie.so
//! - [`imgproc`]  -> libArcFilter.so / libeffect.so filters
//! - [`facedet`]  -> libcvface_api.so
//!
//! Everything is pure Rust with no allocation guarantees beyond the buffers
//! the caller supplies: the Java side owns the frame data, we only touch it.

#![deny(unsafe_op_in_unsafe_fn)]
#![cfg_attr(not(feature = "jni"), allow(dead_code))]

pub mod barcode;
pub mod beauty;
pub mod bokeh;
pub mod docscan;
pub mod exif;
pub mod facedet;
pub mod imgproc;
pub mod stitch;
pub mod yuv;

#[cfg(feature = "jni")]
pub mod jni_bridge;

/// Silence unused-helper warnings until more modules are wired.
#[allow(dead_code)]
fn _docs() {}

/// Crate-wide error type. JNI boundary maps these to Java exceptions.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HoshiyomiError {
    /// Input buffer smaller than width*height*expected_bpp.
    BufferTooSmall,
    /// Width or height is zero / not a multiple required by the format.
    BadDimensions,
    /// Unsupported pixel format for this operation.
    UnsupportedFormat,
    /// Decoded payload failed integrity checks.
    InvalidData,
    /// Region falls outside the frame.
    OutOfRange,
    /// Angle not a multiple of 90.
    BadAngle,
}

pub type HoshiyomiResult<T> = Result<T, HoshiyomiError>;

#[cfg(feature = "jni")]
impl From<jni::errors::Error> for HoshiyomiError {
    fn from(_: jni::errors::Error) -> Self {
        HoshiyomiError::InvalidData
    }
}

/// Four-byte pixel formats mirror Android's ImageFormat constants so the JNI
/// bridge can pass `pixelFormat` straight through.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PixelFormat {
    /// Android ImageFormat.YUV_420_888 — 3 separate planes (Y, U, V).
    Yuv420p = 0x23,
    /// NV21: Y plane + interleaved VU.
    Nv21 = 0x11,
    /// NV12: Y plane + interleaved UV.
    Nv12 = 0x12,
    /// RGBA_8888.
    Rgba8888 = 0x01,
    /// Single-plane greyscale (Y only).
    Grey = 0x08,
}

impl PixelFormat {
    #[inline]
    pub fn from_i32(v: i32) -> Option<PixelFormat> {
        match v {
            0x23 => Some(PixelFormat::Yuv420p),
            0x11 => Some(PixelFormat::Nv21),
            0x12 => Some(PixelFormat::Nv12),
            0x01 => Some(PixelFormat::Rgba8888),
            0x08 => Some(PixelFormat::Grey),
            _ => None,
        }
    }
}

/// Frame geometry shared by every pipeline stage.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameSpec {
    pub width: u32,
    pub height: u32,
    pub format: PixelFormat,
}

impl FrameSpec {
    pub fn new(width: u32, height: u32, format: PixelFormat) -> HoshiyomiResult<Self> {
        if width == 0 || height == 0 {
            return Err(HoshiyomiError::BadDimensions);
        }
        Ok(Self { width, height, format })
    }

    /// Rows in the chroma plane: 4:2:0 halves both axes.
    #[inline]
    pub fn chroma_height(&self) -> u32 {
        (self.height + 1) / 2
    }

    #[inline]
    pub fn chroma_width(&self) -> u32 {
        (self.width + 1) / 2
    }
}


