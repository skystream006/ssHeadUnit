package com.tabletaa.av

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.tabletaa.protocol.ChannelId

/**
 * Plays the PCM streams the phone sends on the media, speech and system audio channels.
 * Each channel gets its own [AudioTrack] so streams can overlap exactly like in a car.
 */
class AudioPlayer {

    private val tracks = HashMap<Int, AudioTrack>()

    @Synchronized
    fun start(channelId: Int, sampleRate: Int, channelCount: Int, bitDepth: Int) {
        stop(channelId)
        val channelMask = if (channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val encoding = if (bitDepth == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.w(TAG, "Unsupported audio format for ${ChannelId.name(channelId)}")
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usageOf(channelId))
                    .setContentType(contentTypeOf(channelId))
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * BUFFER_FACTOR)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        tracks[channelId] = track
    }

    @Synchronized
    fun play(channelId: Int, data: ByteArray) {
        val track = tracks[channelId] ?: return
        var offset = 0
        while (offset < data.size) {
            val written = track.write(data, offset, data.size - offset)
            if (written <= 0) return
            offset += written
        }
    }

    @Synchronized
    fun stop(channelId: Int) {
        tracks.remove(channelId)?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
    }

    @Synchronized
    fun stopAll() {
        tracks.keys.toList().forEach { stop(it) }
    }

    private fun usageOf(channelId: Int): Int = when (channelId) {
        ChannelId.SPEECH_AUDIO -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        ChannelId.SYSTEM_AUDIO -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        else -> AudioAttributes.USAGE_MEDIA
    }

    private fun contentTypeOf(channelId: Int): Int = when (channelId) {
        ChannelId.SPEECH_AUDIO -> AudioAttributes.CONTENT_TYPE_SPEECH
        ChannelId.SYSTEM_AUDIO -> AudioAttributes.CONTENT_TYPE_SONIFICATION
        else -> AudioAttributes.CONTENT_TYPE_MUSIC
    }

    private companion object {
        const val TAG = "AudioPlayer"
        const val BUFFER_FACTOR = 4
    }
}
