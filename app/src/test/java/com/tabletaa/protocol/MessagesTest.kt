package com.tabletaa.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {

    @Test
    fun versionResponseIsSixBigEndianBytes() {
        val payload = Messages.versionResponse(1, 1, Messages.Status.OK)
        assertEquals(ControlMessage.VERSION_RESPONSE, Message(ChannelId.CONTROL, false, payload).messageId)
        assertArrayEquals(byteArrayOf(0, 1, 0, 1, 0, 0), Message(ChannelId.CONTROL, false, payload).body())
    }

    @Test
    fun serviceDiscoveryAdvertisesEveryChannel() {
        val payload = Messages.serviceDiscoveryResponse(
            headUnitName = "TabletAA",
            carModel = "Universal",
            carYear = "2024",
            carSerial = "0001",
            manufacturer = "TabletAA",
            model = "Head Unit",
            softwareBuild = "1",
            softwareVersion = "1.0",
            videoConfig = Messages.VideoConfig(Messages.VideoResolution.RES_720p, Messages.VideoFps.FPS_60),
            touchWidth = 1280,
            touchHeight = 720
        )
        val message = Message(ChannelId.CONTROL, true, payload)
        assertEquals(ControlMessage.SERVICE_DISCOVERY_RESPONSE, message.messageId)

        val channels = ArrayList<Int>()
        var headUnitName = ""
        message.reader().forEach { field ->
            when (field.number) {
                1 -> field.reader.findVarint(1)?.let { channels += it.toInt() }
                2 -> headUnitName = field.string
            }
        }
        assertEquals("TabletAA", headUnitName)
        assertEquals(
            listOf(
                ChannelId.VIDEO,
                ChannelId.MEDIA_AUDIO,
                ChannelId.SPEECH_AUDIO,
                ChannelId.SYSTEM_AUDIO,
                ChannelId.INPUT,
                ChannelId.SENSOR
            ),
            channels
        )
    }

    @Test
    fun parsesChannelOpenRequest() {
        val request = withMessageId(
            ControlMessage.CHANNEL_OPEN_REQUEST,
            ProtoWriter().int32(1, 0).int32(2, ChannelId.MEDIA_AUDIO).toByteArray()
        )
        assertEquals(
            ChannelId.MEDIA_AUDIO,
            Messages.parseChannelOpenRequest(Message(ChannelId.CONTROL, true, request))
        )
    }

    @Test
    fun parsesMediaWithTimestamp() {
        val media = byteArrayOf(0x11, 0x22, 0x33)
        val body = ByteArray(8) { 0 }.also { it[7] = 5 } + media
        val (timestamp, data) = Messages.parseMediaWithTimestamp(
            Message(ChannelId.VIDEO, true, withMessageId(AvMessage.MEDIA_WITH_TIMESTAMP_INDICATION, body))
        )
        assertEquals(5L, timestamp)
        assertArrayEquals(media, data)
    }

    @Test
    fun encodesMultiPointerTouchEvent() {
        val payload = Messages.touchEvent(
            timestampNanos = 12_000L,
            action = Messages.TouchAction.DRAG,
            actionIndex = 1,
            pointers = listOf(Triple(10, 20, 0), Triple(30, 40, 1))
        )
        val message = Message(ChannelId.INPUT, true, payload)
        assertEquals(InputMessage.INPUT_EVENT_INDICATION, message.messageId)
        assertEquals(12_000L, message.reader().findVarint(1))

        val touch = message.reader().findBytes(2)!!
        val points = ArrayList<Pair<Int, Int>>()
        var action = -1
        ProtoReader(touch).forEach { field ->
            when (field.number) {
                1 -> {
                    val x = field.reader.findVarint(1)!!.toInt()
                    val y = field.reader.findVarint(2)!!.toInt()
                    points += x to y
                }
                3 -> action = field.int
            }
        }
        assertEquals(listOf(10 to 20, 30 to 40), points)
        assertEquals(Messages.TouchAction.DRAG, action)
    }

    @Test
    fun mediaAckCarriesSessionId() {
        val message = Message(ChannelId.VIDEO, true, Messages.mediaAck(7))
        assertEquals(AvMessage.MEDIA_ACK_INDICATION, message.messageId)
        assertEquals(7L, message.reader().findVarint(1))
    }

    @Test
    fun sensorEventsAreDistinct() {
        val driving = Message(ChannelId.SENSOR, true, Messages.drivingStatusEvent())
        val night = Message(ChannelId.SENSOR, true, Messages.nightModeEvent(true))
        assertEquals(SensorMessage.SENSOR_EVENT_INDICATION, driving.messageId)
        assertEquals(SensorMessage.SENSOR_EVENT_INDICATION, night.messageId)
        assertTrue(driving.reader().findBytes(11) != null)
        assertEquals(1L, ProtoReader(night.reader().findBytes(10)!!).findVarint(1))
    }
}
