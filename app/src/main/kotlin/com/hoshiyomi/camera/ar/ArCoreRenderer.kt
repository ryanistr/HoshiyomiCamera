package com.hoshiyomi.camera.ar

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore renderer for the AR mode — the Stargazer counterpart of the reference camera app's
 * `GLSurfaceViewBackgroundController` + `ARPreview`: ARCore owns the camera for
 * this mode and renders its background directly; the Compose layer draws the
 * chrome on top.
 *
 * The camera background is ARCore's external OES texture; the marker is a
 * semi-transparent tile placed via [queueTap]'s hit test against detected
 * planes. Everything else in the app keeps the raw Camera2 pipeline.
 */
class ArCoreRenderer(
    private val context: Context,
    private val view: GLSurfaceView
) : GLSurfaceView.Renderer {

    /** Frame telemetry for the Compose status line. */
    var onTelemetry: ((tracking: Boolean, planes: Int, anchors: Int) -> Unit)? = null

    private var session: Session? = null
    private var cameraTexture = 0
    private var bgProgram = 0
    private var bgPos = 0
    private var bgUv = 0
    private var bgSampler = 0
    private var markerProgram = 0
    private var markerMvp = 0
    private var markerPos = 0
    private var markerColor = 0
    private var width = 0
    private var height = 0
    private var resumeAttempts = 0

    private val anchors = mutableListOf<Anchor>()
    private var pendingTapX = -1f
    private var pendingTapY = -1f

    // GLES20's attribute pointers take direct buffers; wrap the static arrays once.
    // GLES20's attribute pointers take direct buffers; wrap the static arrays once.
    private val quadClipBuffer: FloatBuffer = makeDirectBuffer(QUAD_CLIP)
    private val markerVertsBuffer: FloatBuffer = makeDirectBuffer(MARKER_VERTS)

    /** Queue a viewfinder tap for plane hit-testing on the next GL frame. */
    fun queueTap(x: Float, y: Float) {
        pendingTapX = x
        pendingTapY = y
    }

    fun clearAnchors() {
        view.queueEvent {
            anchors.forEach { runCatching { it.detach() } }
            anchors.clear()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        runCatching {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            cameraTexture = createCameraTexture()
            bgProgram = createProgram(BG_VERTEX, BG_FRAGMENT)
            bgPos = GLES20.glGetAttribLocation(bgProgram, "aPosition")
            bgUv = GLES20.glGetAttribLocation(bgProgram, "aTexCoord")
            bgSampler = GLES20.glGetUniformLocation(bgProgram, "sTexture")
            markerProgram = createProgram(MARKER_VERTEX, MARKER_FRAGMENT)
            markerMvp = GLES20.glGetUniformLocation(markerProgram, "uMvp")
            markerPos = GLES20.glGetAttribLocation(markerProgram, "aPosition")
            markerColor = GLES20.glGetUniformLocation(markerProgram, "uColor")
            createAndResumeSession()
        }.onFailure { Log.e(TAG, "AR surface setup failed", it) }
    }

    private fun createAndResumeSession() {
        val s = Session(context)
        val cfg = Config(s)
        cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        cfg.depthMode = Config.DepthMode.DISABLED
        cfg.lightEstimationMode = Config.LightEstimationMode.DISABLED
        s.configure(cfg)
        s.setCameraTextureName(cameraTexture)
        s.resume()
        session = s
        Log.d(TAG, "AR session resumed")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
        // The activity is portrait (natural) so the display rotation is 0;
        // ARCore needs the window geometry to map its camera UVs upright.
        runCatching { session?.setDisplayGeometry(0, w, h) }
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = session ?: return
        val frame = try {
            s.update()
        } catch (e: Exception) {
            // The camera can be briefly unavailable while the previous Camera2
            // session drains; one retry after a beat recovers it.
            if (resumeAttempts++ < 3) {
                view.postDelayed({ runCatching { s.resume() } }, 800)
            }
            return
        }
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        drawBackground(frame)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        if (tracking) {
            anchors.removeAll { it.trackingState == TrackingState.STOPPED }
            anchors.forEach { drawMarker(camera, it) }
            handlePendingTap(frame)
        }
        val planes = frame.getUpdatedTrackables(Plane::class.java)
            .count { it.trackingState == TrackingState.TRACKING }
        onTelemetry?.invoke(tracking, planes, anchors.size)
    }

    private fun handlePendingTap(frame: Frame) {
        if (pendingTapX < 0) return
        val x = pendingTapX / width.toFloat()
        val y = pendingTapY / height.toFloat() // 0..1, top-left origin
        pendingTapX = -1f
        runCatching {
            val hit = frame.hitTest(x, y).firstOrNull { hitResult ->
                val trackable = hitResult.trackable
                trackable is Plane && trackable.isPoseInPolygon(hitResult.hitPose)
            }
            if (hit != null) {
                anchors.add(hit.createAnchor())
                Log.d(TAG, "anchor placed: ${anchors.size}")
            }
        }.onFailure { Log.w(TAG, "hit test failed: $it") }
    }

    private fun drawBackground(frame: Frame) {
        // ARCore maps its landscape sensor frame onto this display geometry
        // through the transformed UVs, so the quad is simply full-clip-space.
        runCatching {
            val srcUv: FloatBuffer = ByteBuffer.allocateDirect(QUAD_UV.size * 4).asFloatBuffer()
            srcUv.put(QUAD_UV)
            srcUv.flip()
            val dstUv: FloatBuffer = ByteBuffer.allocateDirect(srcUv.remaining() * 4).asFloatBuffer()
            frame.transformDisplayUvCoords(srcUv, dstUv)
            dstUv.flip()
            val uvBuffer = dstUv
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(bgProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
            GLES20.glUniform1i(bgSampler, 0)
            GLES20.glEnableVertexAttribArray(bgPos)
            GLES20.glVertexAttribPointer(bgPos, 2, GLES20.GL_FLOAT, false, 0, quadClipBuffer)
            GLES20.glEnableVertexAttribArray(bgUv)
            GLES20.glVertexAttribPointer(bgUv, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDisableVertexAttribArray(bgPos)
            GLES20.glDisableVertexAttribArray(bgUv)
        }.onFailure { Log.w(TAG, "background draw failed: $it") }
    }

    private fun drawMarker(camera: Camera, anchor: Anchor) {
        runCatching {
            val pose = anchor.pose
            val model = FloatArray(16)
            Matrix.setIdentityM(model, 0)
            val t = FloatArray(3)
            pose.getTranslation(t, 0)
            Matrix.translateM(model, 0, t[0], t[1], t[2])
            val q = FloatArray(4)
            pose.getRotationQuaternion(q, 0)
            val angle = Math.toDegrees((2.0 * Math.acos(q[3].toDouble()))).toFloat()
            Matrix.rotateM(model, 0, angle, q[0], q[1], q[2])

            val proj = FloatArray(16)
            val viewM = FloatArray(16)
            val mv = FloatArray(16)
            val mvp = FloatArray(16)
            camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
            camera.getViewMatrix(viewM, 0)
            Matrix.multiplyMM(mv, 0, viewM, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

            GLES20.glUseProgram(markerProgram)
            GLES20.glUniformMatrix4fv(markerMvp, 1, false, mvp, 0)
            GLES20.glUniform4f(markerColor, 0.2f, 0.7f, 1f, 0.7f)
            GLES20.glEnableVertexAttribArray(markerPos)
            GLES20.glVertexAttribPointer(markerPos, 3, GLES20.GL_FLOAT, false, 0, markerVertsBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDisableVertexAttribArray(markerPos)
        }.onFailure { Log.w(TAG, "marker draw failed: $it") }
    }

    fun resume() {
        view.queueEvent {
            runCatching { session?.resume() }
                .onFailure {
                    Log.w(TAG, "AR resume failed, retrying: $it")
                    view.postDelayed({ resume() }, 800)
                }
        }
    }

    fun pause() {
        view.queueEvent { runCatching { session?.pause() } }
    }

    /** Closes the session, releasing the camera back to the Camera2 pipeline. */
    fun release() {
        view.queueEvent {
            runCatching {
                anchors.forEach { it.detach() }
                anchors.clear()
                session?.close()
                session = null
                Log.d(TAG, "AR session closed")
            }.onFailure { Log.w(TAG, "AR release failed: $it") }
        }
    }

    private fun createCameraTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        return ids[0]
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, src: String) = IntArray(1).also { out ->
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "shader compile: ${GLES20.glGetShaderInfoLog(s)}")
                GLES20.glDeleteShader(s)
                throw IllegalStateException("shader compile failed")
            }
            out[0] = s
        }[0]
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "program link: ${GLES20.glGetProgramInfoLog(program)}")
            throw IllegalStateException("program link failed")
        }
        return program
    }

    companion object {
        private const val TAG = "ArCoreRenderer"
        // Converts the static float arrays into direct buffers, which ARCore's
        // native code needs.
        private fun makeDirectBuffer(data: FloatArray): FloatBuffer {
            val buf = ByteBuffer.allocateDirect(data.size * 4).asFloatBuffer() as FloatBuffer
            buf.put(data)
            buf.flip()
            return buf
        }

        // Full-clip-space quad (two triangles) for the camera background.
        private val QUAD_CLIP = floatArrayOf(
            -1f, -1f, 1f, -1f, 1f, 1f,
            -1f, -1f, 1f, 1f, -1f, 1f
        )
        private val QUAD_UV = floatArrayOf(
            0f, 0f, 1f, 0f, 1f, 1f,
            0f, 0f, 1f, 1f, 0f, 1f
        )
        // 20 cm marker tile lying in the anchor's local XZ plane.
        private val MARKER_VERTS = floatArrayOf(
            -0.1f, 0f, -0.1f, 0.1f, 0f, -0.1f, 0.1f, 0f, 0.1f,
            -0.1f, 0f, -0.1f, 0.1f, 0f, 0.1f, -0.1f, 0f, 0.1f
        )
        private const val BG_VERTEX = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = aPosition;
            }
        """
        private const val BG_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
        private const val MARKER_VERTEX = """
            attribute vec4 aPosition;
            uniform mat4 uMvp;
            void main() {
                gl_Position = uMvp * aPosition;
            }
        """
        private const val MARKER_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
