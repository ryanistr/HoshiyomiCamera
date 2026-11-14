package com.hoshiyomi.camera.native

import androidx.annotation.Keep

/**
 * JNI surface into `libhoshiomi_core.so`.
 *
 * One declaration per Rust entry point in `jni_bridge.rs`. Constants hold the
 * `System.loadLibrary` name so tests can assert the library is present.
 */
@Keep
object NativeCore {
    const val LIB_NAME = "hoshiomi_core"

    fun ensureLoaded() {
        runCatching { System.loadLibrary(LIB_NAME) }
    }

    external fun yuv420ToRgba(
        width: Int, height: Int,
        y: ByteArray, yStride: Int,
        u: ByteArray, uStride: Int, uPx: Int,
        v: ByteArray, vStride: Int, vPx: Int,
        out: ByteArray
    ): Int

    external fun nv21ToRgba(width: Int, height: Int, src: ByteArray, out: ByteArray): Int
    external fun rgbaToNv21(width: Int, height: Int, src: ByteArray, out: ByteArray): Int

    /** Returns packed (width shl 16) or height of the rotated buffer. */
    external fun rotate(
        src: ByteArray, width: Int, height: Int, bpp: Int, angle: Int, out: ByteArray
    ): Int

    external fun mirror(
        src: ByteArray, width: Int, height: Int, bpp: Int, flipV: Boolean, out: ByteArray
    ): Int

    external fun scale(
        src: ByteArray, srcW: Int, srcH: Int, bpp: Int, dstW: Int, dstH: Int, out: ByteArray
    ): Int

    external fun crop(
        src: ByteArray, srcW: Int, srcH: Int, bpp: Int,
        x: Int, y: Int, w: Int, h: Int, out: ByteArray
    ): Int

    external fun brightnessContrast(
        rgba: ByteArray, width: Int, height: Int, brightness: Float, contrast: Float
    ): Int

    external fun saturationWarmth(
        rgba: ByteArray, width: Int, height: Int, saturation: Float, warmth: Float
    ): Int

    external fun applyCurve(rgba: ByteArray, curve: ByteArray): Int

    external fun colorMatrix(rgba: ByteArray, width: Int, height: Int, matrix: FloatArray): Int

    external fun gaussianBlur(
        rgba: ByteArray, width: Int, height: Int, radius: Int, out: ByteArray
    ): Int

    external fun vignette(rgba: ByteArray, width: Int, height: Int, strength: Float): Int

    external fun sharpen(
        rgba: ByteArray, width: Int, height: Int, amount: Float, out: ByteArray
    ): Int

    external fun depthOfField(
        rgba: ByteArray, depth: ByteArray, width: Int, height: Int, maxRadius: Int, out: ByteArray
    ): Int

    external fun compositeBokeh(
        sharp: ByteArray, blurred: ByteArray, mask: ByteArray,
        width: Int, height: Int, edgeSoftness: Int, out: ByteArray
    ): Int

    external fun portraitSpot(
        rgba: ByteArray, width: Int, height: Int,
        cx: Float, cy: Float, faceRadius: Float, intensity: Float
    ): Int

    external fun apertureRadius(fStop: Float, shortSide: Int): Int

    /** Frequency-separation skin smoothing over the whole frame. */
    external fun skinSmooth(rgba: ByteArray, width: Int, height: Int, intensity: Float, out: ByteArray): Int

    /** Brighten + soften a single face oval (normalized coords). */
    external fun faceEnhance(
        rgba: ByteArray, width: Int, height: Int,
        fx: Float, fy: Float, fw: Float, fh: Float, brighten: Float, soften: Float
    ): Int

    /** Full beauty pipeline: global smoothing + per-face enhancement. */
    external fun beautyPass(
        rgba: ByteArray, width: Int, height: Int, faces: FloatArray,
        smoothing: Float, brighten: Float, soften: Float, out: ByteArray
    ): Int

    /** JPEG EXIF orientation in degrees (0/90/180/270), 0 if absent. */
    external fun exifOrientation(jpeg: ByteArray): Int

    /** "Make Model" read from JPEG EXIF, empty if absent. */
    external fun exifMakeModel(jpeg: ByteArray): String

    /** Builds a JPEG APP1/Exif segment. [spec] = "kind:name:value,…" records. */
    external fun exifBuildApp1(spec: String): ByteArray

    /** QR payload, or null/empty when no code is found. */
    external fun decodeQr(grey: ByteArray, width: Int, height: Int): String?

    /** Faces as flat [x0,y0,w0,h0, …] in normalized [0,1] coordinates. */
    external fun detectFaces(grey: ByteArray, width: Int, height: Int): FloatArray

    /** Exposure bias to apply so faces land mid-tone; 0f when no faces. */
    external fun faceMeteringBias(
        grey: ByteArray, width: Int, height: Int, faces: IntArray
    ): Float

    external fun estimateDx(
        a: ByteArray, b: ByteArray, width: Int, height: Int, maxShift: Int
    ): Int

    external fun stitchPair(
        left: ByteArray, right: ByteArray, width: Int, height: Int, bpp: Int, dx: Int,
        out: ByteArray
    ): Int

    /** Multi-frame panorama stitch; [dxs] has frames.size - 1 entries.
     * Returns the canvas width. */
    external fun stitchHorizontal(
        frames: Array<ByteArray>, width: Int, height: Int, bpp: Int, dxs: IntArray,
        out: ByteArray
    ): Int

    // ---- document scanning ----

    /** Sheet quad as [tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y], or null. */
    external fun detectSheet(grey: ByteArray, width: Int, height: Int): FloatArray?

    /** Perspective-rectifies [quad] into outW x outH RGBA. */
    external fun rectifyDocument(
        rgba: ByteArray, width: Int, height: Int, quad: FloatArray,
        outW: Int, outH: Int, out: ByteArray
    ): Int

    /** Whitens paper background, deepens ink. `whitePoint` from otsuThreshold. */
    external fun enhanceDocument(rgba: ByteArray, width: Int, height: Int, whitePoint: Int): Int

    /** Otsu threshold of the frame; serves as the document white point. */
    external fun otsuThreshold(grey: ByteArray, width: Int, height: Int): Int

    /** Multi-frame night stacking: averages N RGBA frames (1/sqrt(N) noise). */
    external fun meanStack(frames: Array<ByteArray>, width: Int, height: Int, out: ByteArray): Int

    /** Night shadow lift after stacking. */
    external fun nightLift(rgba: ByteArray, width: Int, height: Int, shadowPoint: Int, strength: Float): Int
}
