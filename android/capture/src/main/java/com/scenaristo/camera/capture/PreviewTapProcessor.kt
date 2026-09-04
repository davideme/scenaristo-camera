package com.scenaristo.camera.capture

import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The single GL pass ADR-0018 chose: it taps the preview stream CameraX already
 * opens and renders each frame twice — to the viewfinder surface CameraX asks
 * for, and to a reader we own whose frames feed the metering loop (ADR-0005) and
 * the MJPEG encoder (ADR-0008).
 *
 * It exists because a UHD recording and an `ImageAnalysis` cannot be bound
 * together on the reference device (#20): there is no second CPU-readable camera
 * stream to be had, so we make one from the preview.
 *
 * Everything here runs on one thread that owns the EGL context. CameraX calls
 * [onInputSurface] and [onOutputSurface] from its own executor, so both hop onto
 * that thread rather than touching GL state where they land.
 *
 * Delete this class the day CameraX binds UHD alongside `ImageAnalysis`
 * (ADR-0018's revisit trigger, #27) — not one release later.
 */
class PreviewTapProcessor(
    /** Longest edge of the frames handed to [onFrame]; 960x540 is ADR-0008's preview size. */
    private val readerLongEdge: Int = 960,
    /**
     * Called on the GL thread for every cropped frame. The receiver **must**
     * close the image: the reader stalls at [READER_BUFFERS] outstanding frames,
     * which is the newest-frame-wins behaviour ADR-0008 wants and a deadlock if
     * anyone holds on.
     */
    private val onFrame: (android.media.Image) -> Unit = { it.close() },
) : SurfaceProcessor {

    private val thread = HandlerThread("preview-tap").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSize = Size(0, 0)

    /** CameraX's outputs, plus ours. Ours is the one the crop applies to. */
    private val outputs = mutableMapOf<SurfaceOutput, EGLSurface>()
    private var reader: ImageReader? = null
    private var readerSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val texMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)

    private val vertices: ByteBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .apply { asFloatBuffer().put(VERTICES).position(0) }

    // --- SurfaceProcessor ---------------------------------------------------

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            ensureEgl()
            inputSize = request.resolution

            textureId = createExternalTexture()
            val texture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(inputSize.width, inputSize.height)
                setOnFrameAvailableListener({ handler.post(::drawFrame) }, handler)
            }
            surfaceTexture = texture

            val surface = Surface(texture)
            request.provideSurface(surface, { it.run() }) { result ->
                // CameraX is done with the surface; the texture outlives nothing.
                surface.release()
                texture.release()
                if (surfaceTexture === texture) surfaceTexture = null
                Log.d(TAG, "input surface released: ${result.resultCode}")
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        handler.post {
            ensureEgl()
            val surface = output.getSurface({ it.run() }) { event ->
                handler.post {
                    outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it) }
                    output.close()
                    Log.d(TAG, "output surface closed: ${event.eventCode}")
                }
            }
            outputs[output] = createWindowSurface(surface)
            ensureReader(output.size)
        }
    }

    // --- the pass -----------------------------------------------------------

    private fun drawFrame() {
        val texture = surfaceTexture ?: return
        texture.updateTexImage()
        texture.getTransformMatrix(texMatrix)

        for ((output, eglSurface) in outputs) {
            // CameraX knows what its own surface expects -- rotation, mirroring,
            // and the crop rect from any ViewPort. Take its matrix as given
            // rather than composing our own on top of it.
            output.updateTransformMatrix(outMatrix, texMatrix)
            drawTo(eglSurface, outMatrix, output.size, texture.timestamp)
        }

        // Our reader is the one that must match the recording's framing, because
        // its frames are what the browser shows and what the meter weighs
        // (ADR-0018). CameraX has no opinion about this surface, so the crop is
        // ours to apply.
        if (readerSurface != EGL14.EGL_NO_SURFACE) {
            reader?.let { r ->
                Matrix.multiplyMM(outMatrix, 0, texMatrix, 0, cropMatrix, 0)
                drawTo(readerSurface, outMatrix, Size(r.width, r.height), texture.timestamp)
            }
        }
    }

    private fun drawTo(eglSurface: EGLSurface, matrix: FloatArray, size: Size, timestampNs: Long) {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            Log.w(TAG, "eglMakeCurrent failed; dropping frame")
            return
        }
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glUseProgram(program)

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)

        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoord)
        vertices.position(8)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Presentation time keeps consumers that care about pacing honest; the
        // reader ignores it, a recording surface would not.
        android.opengl.EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestampNs)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    // --- setup --------------------------------------------------------------

    private fun ensureEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) return

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no EGL config"
        }
        config = configs[0]

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        // A 1x1 pbuffer so there is a current context before any window surface
        // exists; compiling the program needs one.
        val pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        program = buildProgram()
    }

    private fun createWindowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)

    /**
     * The reader is sized from the crop, not from the raw preview: cropping first
     * and sizing second means the frames the browser gets are already the
     * recording's shape, and nothing downstream has to know about the difference.
     */
    private fun ensureReader(recordingSize: Size) {
        if (reader != null || inputSize.width == 0) return

        val region = PreviewCrop.centredCrop(
            inputSize.width,
            inputSize.height,
            recordingSize.width,
            recordingSize.height,
        )
        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, region.offsetX, region.offsetY, 0f)
        Matrix.scaleM(cropMatrix, 0, region.scaleX, region.scaleY, 1f)

        val (croppedWidth, croppedHeight) = PreviewCrop.croppedSize(inputSize.width, inputSize.height, region)
        val scale = readerLongEdge.toFloat() / maxOf(croppedWidth, croppedHeight)
        val width = (croppedWidth * scale).toInt().coerceAtLeast(2) and 1.inv()
        val height = (croppedHeight * scale).toInt().coerceAtLeast(2) and 1.inv()

        // RGBA_8888 rather than PRIVATE: PRIVATE is GPU-only, and these frames
        // exist to be read — the JPEG encoder for the browser preview (ADR-0008)
        // and the metering loop (ADR-0005) both need the pixels on the CPU. The
        // usage flags say the surface is a GL render target that the CPU then
        // reads, which is exactly what this pass does.
        reader = ImageReader.newInstance(
            width,
            height,
            android.graphics.PixelFormat.RGBA_8888,
            READER_BUFFERS,
            android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN,
        ).also { r ->
            r.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                // acquireLatestImage drops anything older, which is ADR-0008's
                // newest-frame-wins. The callback owns closing it.
                onFrame(image)
            }, handler)
            readerSurface = createWindowSurface(r.surface)
        }
        Log.d(TAG, "tap reader ${width}x$height from ${inputSize.width}x${inputSize.height}, crop=$region")
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, vertex)
            GLES20.glAttachShader(p, fragment)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        }
    }

    private fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
    }

    /** Releases the thread and every GL object. Safe to call twice. */
    fun release() {
        handler.post {
            outputs.values.forEach { EGL14.eglDestroySurface(display, it) }
            outputs.keys.forEach { it.close() }
            outputs.clear()
            if (readerSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, readerSurface)
            readerSurface = EGL14.EGL_NO_SURFACE
            reader?.close()
            reader = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "PreviewTap"

        /** Not in EGL14; the value from eglext.h. Lets the surface back a video encoder later. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        /**
         * Two: enough that a consumer can hold one while the next arrives, few
         * enough that a slow consumer stalls the tap rather than queueing stale
         * frames. ADR-0008 wants newest-wins, not complete.
         */
        const val READER_BUFFERS = 2

        /** x, y, u, v for a full-surface triangle strip. */
        val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
