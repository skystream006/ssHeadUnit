package com.tabletaa.protocol

/**
 * Minimal protocol buffers (proto2 wire format) reader.
 *
 * Fields are streamed to the caller through [forEach]; unknown fields are skipped so that the
 * head unit stays forward compatible with newer phone builds.
 */
class ProtoReader(private val buffer: ByteArray, private var pos: Int = 0, private val end: Int = buffer.size) {

    /** A single decoded field. Only the member matching the wire type is meaningful. */
    class Field(val number: Int, val wireType: Int, val varint: Long, val data: ByteArray?) {
        val int: Int get() = varint.toInt()
        val bool: Boolean get() = varint != 0L
        val string: String get() = data?.toString(Charsets.UTF_8) ?: ""
        val reader: ProtoReader get() = ProtoReader(data ?: ByteArray(0))
    }

    fun forEach(action: (Field) -> Unit) {
        while (pos < end) {
            val tag = readVarint()
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (number == 0) throw IllegalArgumentException("invalid field number 0")
            when (wireType) {
                ProtoWriter.WIRE_VARINT -> action(Field(number, wireType, readVarint(), null))
                ProtoWriter.WIRE_FIXED64 -> action(Field(number, wireType, readFixed(8), null))
                ProtoWriter.WIRE_FIXED32 -> action(Field(number, wireType, readFixed(4), null))
                ProtoWriter.WIRE_LENGTH -> {
                    val length = readVarint().toInt()
                    if (length < 0 || pos + length > end) throw IllegalArgumentException("truncated message")
                    val data = buffer.copyOfRange(pos, pos + length)
                    pos += length
                    action(Field(number, wireType, length.toLong(), data))
                }
                else -> throw IllegalArgumentException("unsupported wire type $wireType")
            }
        }
    }

    /** Returns the varint value of [field], or null when the field is absent. */
    fun findVarint(field: Int): Long? {
        var result: Long? = null
        forEach { if (it.number == field && it.wireType == ProtoWriter.WIRE_VARINT) result = it.varint }
        return result
    }

    /** Returns the length delimited value of [field], or null when the field is absent. */
    fun findBytes(field: Int): ByteArray? {
        var result: ByteArray? = null
        forEach { if (it.number == field && it.wireType == ProtoWriter.WIRE_LENGTH) result = it.data }
        return result
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw IllegalArgumentException("truncated varint")
            if (shift > 63) throw IllegalArgumentException("varint too long")
            val b = buffer[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun readFixed(size: Int): Long {
        if (pos + size > end) throw IllegalArgumentException("truncated fixed field")
        var result = 0L
        for (i in 0 until size) {
            result = result or ((buffer[pos + i].toLong() and 0xFF) shl (8 * i))
        }
        pos += size
        return result
    }
}
