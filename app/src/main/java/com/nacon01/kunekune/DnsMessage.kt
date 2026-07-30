package com.nacon01.kunekune

/** A parsed one-question DNS query. The question bytes are retained verbatim. */
data class DnsQuery(
    val transactionId: Int,
    val flags: Int,
    val hostname: String,
    val question: ByteArray,
    val originalPayload: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is DnsQuery &&
        transactionId == other.transactionId && flags == other.flags &&
        hostname == other.hostname && question.contentEquals(other.question) &&
        originalPayload.contentEquals(other.originalPayload)

    override fun hashCode(): Int = 31 * transactionId + flags + hostname.hashCode()
}

/** Minimal DNS wire parser/builder used by the local DNS-only VPN. */
object DnsMessage {
    private const val HEADER_LENGTH = 12
    private const val MAX_NAME_LENGTH = 253

    fun parseQuery(payload: ByteArray): DnsQuery? {
        if (payload.size < HEADER_LENGTH || payload.size > 4096) return null
        val flags = readU16(payload, 2)
        if ((flags and 0x8000) != 0) return null
        val questionCount = readU16(payload, 4)
        if (questionCount != 1) return null
        val position = intArrayOf(HEADER_LENGTH)
        val labels = ArrayList<String>()
        var nameLength = 0
        while (true) {
            val labelLength = payload.getOrNull(position[0]++)?.toInt()?.and(0xff) ?: return null
            if (labelLength == 0) break
            if ((labelLength and 0xc0) != 0 || labelLength > 63) return null
            if (position[0] + labelLength > payload.size) return null
            val labelBytes = payload.copyOfRange(position[0], position[0] + labelLength)
            if (labelBytes.any { (it.toInt() and 0xff) !in 0x21..0x7e }) return null
            labels += String(labelBytes, Charsets.US_ASCII)
            position[0] += labelLength
            nameLength += labelLength + 1
            if (nameLength > MAX_NAME_LENGTH) return null
        }
        if (labels.isEmpty()) return null
        if (position[0] + 4 > payload.size) return null
        val questionEnd = position[0] + 4
        val qclass = readU16(payload, position[0] + 2)
        if (qclass != 1) return null
        return DnsQuery(
            transactionId = readU16(payload, 0),
            flags = flags,
            hostname = labels.joinToString("."),
            question = payload.copyOfRange(HEADER_LENGTH, questionEnd),
            originalPayload = payload.copyOf()
        )
    }

    fun buildNxdomainResponse(query: DnsQuery): ByteArray = buildErrorResponse(query, 3)

    fun buildServfailResponse(query: DnsQuery): ByteArray = buildErrorResponse(query, 2)

    private fun buildErrorResponse(query: DnsQuery, rcode: Int): ByteArray {
        val response = ByteArray(HEADER_LENGTH + query.question.size)
        writeU16(response, 0, query.transactionId)
        // QR, opcode, RD, and CD are meaningful query flags. RA/AD/TC and all
        // records are omitted because this local responder did not resolve it.
        val responseFlags = 0x8000 or (query.flags and 0x7910) or (rcode and 0x000f)
        writeU16(response, 2, responseFlags)
        writeU16(response, 4, 1)
        query.question.copyInto(response, HEADER_LENGTH)
        return response
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
