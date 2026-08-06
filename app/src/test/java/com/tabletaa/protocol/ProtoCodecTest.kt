package com.tabletaa.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoCodecTest {

    @Test
    fun writesAndReadsScalarFields() {
        val encoded = ProtoWriter()
            .int32(1, 300)
            .bool(2, true)
            .string(3, "TabletAA")
            .varint(4, 1_234_567_890_123L)
            .toByteArray()

        val reader = ProtoReader(encoded)
        assertEquals(300L, ProtoReader(encoded).findVarint(1))
        assertEquals(1L, ProtoReader(encoded).findVarint(2))
        assertEquals("TabletAA", String(reader.findBytes(3)!!, Charsets.UTF_8))
        assertEquals(1_234_567_890_123L, ProtoReader(encoded).findVarint(4))
    }

    @Test
    fun writesNestedMessages() {
        val encoded = ProtoWriter()
            .message(1) {
                int32(1, 7)
                string(2, "nested")
            }
            .toByteArray()

        val nested = ProtoReader(encoded).findBytes(1)!!
        assertEquals(7L, ProtoReader(nested).findVarint(1))
        assertEquals("nested", String(ProtoReader(nested).findBytes(2)!!, Charsets.UTF_8))
    }

    @Test
    fun matchesCanonicalProtobufEncoding() {
        // field 1, varint 150 is the canonical protobuf example: 08 96 01
        assertArrayEquals(byteArrayOf(0x08, 0x96.toByte(), 0x01), ProtoWriter().int32(1, 150).toByteArray())
    }

    @Test
    fun skipsUnknownFieldsAndReportsMissingOnes() {
        val encoded = ProtoWriter().fixed64(9, 42L).int32(1, 5).toByteArray()
        assertEquals(5L, ProtoReader(encoded).findVarint(1))
        assertNull(ProtoReader(encoded).findVarint(2))
    }

    @Test
    fun iteratesEveryRepeatedField() {
        val encoded = ProtoWriter().int32(1, 1).int32(1, 2).int32(1, 3).toByteArray()
        val values = ArrayList<Int>()
        ProtoReader(encoded).forEach { if (it.number == 1) values += it.int }
        assertEquals(listOf(1, 2, 3), values)
    }

    @Test
    fun rejectsTruncatedMessages() {
        val encoded = ProtoWriter().string(1, "hello").toByteArray()
        val truncated = encoded.copyOfRange(0, encoded.size - 2)
        val failed = runCatching { ProtoReader(truncated).forEach { } }.isFailure
        assertTrue(failed)
    }
}
