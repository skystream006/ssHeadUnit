package com.tabletaa.session

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.tabletaa.av.AudioPlayer
import com.tabletaa.av.VideoRenderer
import com.tabletaa.protocol.Messages
import com.tabletaa.transport.Aoap
import com.tabletaa.transport.Transport

/**
 * Single place that owns the live projection session: the USB link, the protocol session and the
 * audio/video output. The activity only attaches a [Surface] and forwards touch events.
 */
object HeadUnitController : HeadUnitListener {

    private const val TAG = "HeadUnitController"

    /** Projected display size; the phone renders its UI at exactly this resolution. */
    const val VIDEO_WIDTH = 1280
    const val VIDEO_HEIGHT = 720

    private val videoRenderer = VideoRenderer()
    private val audioPlayer = AudioPlayer()
    private val config = HeadUnitConfig(touchWidth = VIDEO_WIDTH, touchHeight = VIDEO_HEIGHT)

    private var transport: Transport? = null
    private var session: HeadUnitSession? = null
    private var sessionThread: Thread? = null
    private var surface: Surface? = null
    private var videoStarted = false

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var status: String = "Disconnected"
        private set

    /** Notified on state changes so the UI can show what the head unit is doing. */
    @Volatile
    var statusListener: ((String, Boolean) -> Unit)? = null

    /** Opens a session with a phone that is already in accessory mode. */
    @Synchronized
    fun start(context: Context, device: UsbDevice) {
        if (session != null) return
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        try {
            val sslContext = HeadUnitCredentials.createSslContext(context)
            val link = Aoap.openTransport(manager, device)
            transport = link
            val newSession = HeadUnitSession(link, sslContext, config, this)
            session = newSession
            publish("Connecting to phone…", connected = false)
            sessionThread = Thread({ newSession.run() }, "aa-session").apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start session", e)
            publish(e.message ?: "Unable to start session", connected = false)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        session?.stop()
        session = null
        sessionThread?.join(THREAD_JOIN_MS)
        sessionThread = null
        transport?.close()
        transport = null
        stopVideo()
        audioPlayer.stopAll()
        isConnected = false
        publish("Disconnected", connected = false)
    }

    @Synchronized
    fun attachSurface(newSurface: Surface?) {
        surface = newSurface
        if (newSurface == null) stopVideo() else startVideoIfReady()
    }

    /** Forwards a touch event, scaling view coordinates to the projected resolution. */
    fun onTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val activeSession = session ?: return false
        if (viewWidth <= 0 || viewHeight <= 0) return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> Messages.TouchAction.PRESS
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> Messages.TouchAction.RELEASE
            MotionEvent.ACTION_MOVE -> Messages.TouchAction.DRAG
            else -> return false
        }
        val pointers = (0 until event.pointerCount).map { index ->
            val x = (event.getX(index) * VIDEO_WIDTH / viewWidth).toInt().coerceIn(0, VIDEO_WIDTH - 1)
            val y = (event.getY(index) * VIDEO_HEIGHT / viewHeight).toInt().coerceIn(0, VIDEO_HEIGHT - 1)
            Triple(x, y, event.getPointerId(index))
        }
        activeSession.sendTouchEvent(event.eventTime * 1_000_000L, action, event.actionIndex, pointers)
        return true
    }

    fun setNightMode(night: Boolean) {
        session?.sendNightMode(night)
    }

    // --- session callbacks --------------------------------------------------------------------

    override fun onConnected() {
        isConnected = true
        publish("Connected", connected = true)
        synchronized(this) { startVideoIfReady() }
    }

    override fun onDisconnected(reason: String) {
        Log.i(TAG, "Session ended: $reason")
        isConnected = false
        publish("Disconnected: $reason", connected = false)
        synchronized(this) {
            stopVideo()
            audioPlayer.stopAll()
        }
    }

    override fun onVideoData(data: ByteArray, timestampNanos: Long) {
        videoRenderer.onData(data, timestampNanos)
    }

    override fun onAudioStart(channelId: Int, sampleRate: Int, channelCount: Int, bitDepth: Int) {
        audioPlayer.start(channelId, sampleRate, channelCount, bitDepth)
    }

    override fun onAudioData(channelId: Int, data: ByteArray) {
        audioPlayer.play(channelId, data)
    }

    override fun onAudioStop(channelId: Int) {
        audioPlayer.stop(channelId)
    }

    override fun onLog(message: String) {
        Log.d(TAG, message)
    }

    // --- internals ----------------------------------------------------------------------------

    private fun startVideoIfReady() {
        val target = surface ?: return
        if (videoStarted || !isConnected || !target.isValid) return
        runCatching { videoRenderer.start(target, VIDEO_WIDTH, VIDEO_HEIGHT) }
            .onSuccess { videoStarted = true }
            .onFailure { Log.e(TAG, "Unable to start video decoder", it) }
    }

    private fun stopVideo() {
        if (!videoStarted) return
        videoRenderer.stop()
        videoStarted = false
    }

    private fun publish(text: String, connected: Boolean) {
        status = text
        statusListener?.invoke(text, connected)
    }

    private const val THREAD_JOIN_MS = 1000L
}
