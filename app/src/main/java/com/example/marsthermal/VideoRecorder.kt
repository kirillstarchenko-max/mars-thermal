package com.example.marsthermal

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись MP4 (H.264, аппаратный кодер) в Movies/MaroderTherm. Кадры — готовые Bitmap с сеткой и метками,
 * их рисуем на входную поверхность кодера. Все вызовы из одного потока (потока обработки).
 */
class VideoRecorder(private val ctx: Context) {
    companion object {
        const val W = 1280
        const val H = 1024
        private const val BITRATE = 8_000_000
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var track = -1
    private var muxing = false
    private val info = MediaCodec.BufferInfo()
    private val dst = Rect(0, 0, W, H)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    var fileName = ""
        private set
    val active get() = codec != null

    /** false и текст ошибки в lastError, если запись начать не удалось. */
    var lastError = ""
        private set

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < 29) { lastError = "нужен Android 10+"; return false }
        try {
            fileName = "maroder_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MaroderTherm")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val u = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore не создал файл")
            uri = u
            val fd = ctx.contentResolver.openFileDescriptor(u, "rw")
                ?: throw IllegalStateException("не открыть файл")
            pfd = fd
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = c.createInputSurface()
            c.start()
            codec = c
            track = -1
            muxing = false
            return true
        } catch (t: Throwable) {
            lastError = t.toString()
            release(keep = false)
            return false
        }
    }

    fun frame(bmp: Bitmap) {
        val s = surface ?: return
        val canvas = s.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bmp, null, dst, paint)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
        drain(endOfStream = false)
    }

    /** Остановить и сохранить; false, если записать ничего не успели. */
    fun stop(): Boolean {
        val c = codec ?: return false
        try {
            c.signalEndOfInputStream()
            drain(endOfStream = true)
        } catch (_: Throwable) {}
        val ok = muxing
        release(keep = ok)
        return ok
    }

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        var idle = 0
        while (true) {
            val i = c.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 100) return
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxing) {
                        track = m.addTrack(c.outputFormat)
                        m.start()
                        muxing = true
                    }
                }
                i >= 0 -> {
                    val buf = c.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        m.writeSampleData(track, buf, info)
                    }
                    c.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release(keep: Boolean) {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        try { surface?.release() } catch (_: Throwable) {}
        surface = null
        try { if (muxing) muxer?.stop() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        muxing = false
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
        val u = uri ?: return
        uri = null
        try {
            if (keep && Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(u, done, null, null)
            } else {
                ctx.contentResolver.delete(u, null, null)
            }
        } catch (_: Throwable) {}
    }
}
