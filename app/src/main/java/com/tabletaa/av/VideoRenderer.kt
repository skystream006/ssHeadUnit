package com.tabletaa.av

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the H.264 stream projected by the phone and renders it onto the tablet display.
 */
class VideoRenderer {

    private var codec: MediaCodec? = null
    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    fun start(surface: Surface, width: Int, height: Int) {
        stop()
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val decoder = MediaCodec.createDecoderByType(MIME_TYPE)
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
        running = true
        renderThread = Thread({ renderLoop(decoder) }, "video-render").apply { start() }
    }

    /** Queues one access unit of the H.264 stream. */
    fun onData(data: ByteArray, timestampNanos: Long) {
        val decoder = codec ?: return
        if (!running || data.isEmpty()) return
        try {
            val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) return
            val buffer: ByteBuffer = decoder.getInputBuffer(index) ?: return
            buffer.clear()
            if (buffer.remaining() < data.size) {
                decoder.queueInputBuffer(index, 0, 0, 0, 0)
                return
            }
            buffer.put(data)
            decoder.queueInputBuffer(index, 0, data.size, timestampNanos / 1000, 0)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Dropping video data: ${e.message}")
        }
    }

    fun stop() {
        running = false
        renderThread?.join(THREAD_JOIN_MS)
        renderThread = null
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private fun renderLoop(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val index = decoder.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (index >= 0) {
                    decoder.releaseOutputBuffer(index, true)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Video render loop stopped: ${e.message}")
                return
            }
        }
    }

    private companion object {
        const val TAG = "VideoRenderer"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MAX_INPUT_SIZE = 1024 * 1024
        const val INPUT_TIMEOUT_US = 100_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val THREAD_JOIN_MS = 500L
    }
}
