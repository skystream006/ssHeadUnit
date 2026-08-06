package com.tabletaa.protocol

import java.io.ByteArrayOutputStream

/**
 * Minimal protocol buffers (proto2 wire format) writer.
 *
 * Android Auto messages are protobuf encoded. Only the handful of wire types used by the
 * head unit protocol are supported, which keeps the app free of a code generation step.
 */
class ProtoWriter {

    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        writeVarint(value)
        return this
    }

    fun int32(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1L else 0L)

    fun enum(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun fixed64(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_FIXED64)
        for (i in 0 until 8) {
            out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LENGTH)
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    /** Writes an embedded message built by [block]. */
    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter {
        val nested = ProtoWriter()
        nested.block()
        return bytes(field, nested.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) {
        require(field > 0) { "field number must be positive: $field" }
        writeVarint((field.toLong() shl 3) or wireType.toLong())
    }

    private fun writeVarint(value: Long) {
        var v = value
        while (true) {
            val bits = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(bits)
                return
            }
            out.write(bits or 0x80)
        }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}
