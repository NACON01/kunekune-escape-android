package com.nacon01.kunekune

/** Parsed IPv4/UDP packet with an owned payload copy. */
data class ParsedIpv4UdpPacket(
    val sourceAddress: Int,
    val destinationAddress: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray
)

/** Android-free IPv4/UDP wire helper, including RFC 1071 checksums. */
object Ipv4UdpPacket {
    private const val IPV4_HEADER_LENGTH = 20
    private const val UDP_HEADER_LENGTH = 8

    fun parse(packet: ByteArray): ParsedIpv4UdpPacket? {
        if (packet.size < IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH) return null
        if (((packet[0].toInt() ushr 4)) != 4) return null
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        if (headerLength < IPV4_HEADER_LENGTH || headerLength + UDP_HEADER_LENGTH > packet.size) return null
        val totalLength = readU16(packet, 2)
        if (totalLength < headerLength + UDP_HEADER_LENGTH || totalLength > packet.size) return null
        if ((packet[9].toInt() and 0xff) != 17) return null
        // A DNS proxy cannot safely reconstruct any IPv4 fragments. This includes
        // a first fragment with MF set and a zero fragment offset.
        if ((readU16(packet, 6) and 0x3fff) != 0) return null
        if (checksum(packet, 0, headerLength) != 0) return null
        val udpOffset = headerLength
        val udpLength = readU16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > totalLength) return null
        val sourceAddress = readU32(packet, 12)
        val destinationAddress = readU32(packet, 16)
        val udpChecksum = readU16(packet, udpOffset + 6)
        if (udpChecksum != 0 && udpChecksum(packet, udpOffset, udpLength, sourceAddress, destinationAddress, false) != 0) {
            return null
        }
        return ParsedIpv4UdpPacket(
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = readU16(packet, udpOffset),
            destinationPort = readU16(packet, udpOffset + 2),
            payload = packet.copyOfRange(udpOffset + UDP_HEADER_LENGTH, udpOffset + udpLength)
        )
    }

    fun build(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray
    ): ByteArray {
        require(sourcePort in 0..65535 && destinationPort in 0..65535)
        require(payload.size <= 65507)
        val udpLength = UDP_HEADER_LENGTH + payload.size
        val totalLength = IPV4_HEADER_LENGTH + udpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        writeU16(packet, 2, totalLength)
        packet[8] = 64
        packet[9] = 17
        writeU32(packet, 12, sourceAddress)
        writeU32(packet, 16, destinationAddress)
        writeU16(packet, 10, checksum(packet, 0, IPV4_HEADER_LENGTH))
        writeU16(packet, IPV4_HEADER_LENGTH, sourcePort)
        writeU16(packet, IPV4_HEADER_LENGTH + 2, destinationPort)
        writeU16(packet, IPV4_HEADER_LENGTH + 4, udpLength)
        payload.copyInto(packet, IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH)
        val udpChecksum = udpChecksum(
            packet, IPV4_HEADER_LENGTH, udpLength, sourceAddress, destinationAddress, true
        ).let { if (it == 0) 0xffff else it }
        writeU16(packet, IPV4_HEADER_LENGTH + 6, udpChecksum)
        return packet
    }

    fun address(a: Int, b: Int, c: Int, d: Int): Int =
        ((a and 0xff) shl 24) or ((b and 0xff) shl 16) or
            ((c and 0xff) shl 8) or (d and 0xff)

    private fun udpChecksum(
        packet: ByteArray,
        offset: Int,
        length: Int,
        sourceAddress: Int,
        destinationAddress: Int,
        zeroChecksum: Boolean
    ): Int {
        val pseudo = ByteArray(12 + length)
        writeU32(pseudo, 0, sourceAddress)
        writeU32(pseudo, 4, destinationAddress)
        pseudo[9] = 17
        writeU16(pseudo, 10, length)
        packet.copyInto(pseudo, 12, offset, offset + length)
        if (zeroChecksum) {
            pseudo[12 + 6] = 0
            pseudo[12 + 7] = 0
        }
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)
            index += 2
        }
        if (index < end) sum += (bytes[index].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
