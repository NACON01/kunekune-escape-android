package com.nacon01.kunekune

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsAndIpv4UdpPacketTest {
    @Test fun parsesValidQueryAndRejectsCompressionOrMalformedPackets() {
        val query = query("WWW.Example.com")
        val parsed = DnsMessage.parseQuery(query)
        assertEquals("WWW.Example.com", parsed?.hostname)
        assertNull(DnsMessage.parseQuery(query.copyOf().apply { this[12] = 0xc0.toByte() }))
        assertNull(DnsMessage.parseQuery(query.copyOf().apply { this[2] = 0x81.toByte() }))
        assertNull(DnsMessage.parseQuery(query.copyOf().apply { this[12] = 63 }))
        assertNull(DnsMessage.parseQuery(query.copyOfRange(0, query.size - 1)))
    }

    @Test fun nxdomainPreservesTransactionAndQuestionWithZeroRecords() {
        val parsed = requireNotNull(DnsMessage.parseQuery(query("example.com")))
        val response = DnsMessage.buildNxdomainResponse(parsed)
        assertEquals(parsed.transactionId, u16(response, 0))
        assertTrue(u16(response, 2) and 0x8000 != 0)
        assertEquals(3, u16(response, 2) and 0x000f)
        assertEquals(1, u16(response, 4))
        assertEquals(0, u16(response, 6))
        assertEquals(0, u16(response, 8))
        assertEquals(0, u16(response, 10))
        assertArrayEquals(parsed.question, response.copyOfRange(12, response.size))
    }

    @Test fun servfailRetainsOnlyValidQueryFlagsAndQuestion() {
        val query = query("example.com").apply { this[3] = 0x10 }
        val parsed = requireNotNull(DnsMessage.parseQuery(query))
        val response = DnsMessage.buildServfailResponse(parsed)
        assertEquals(0x8112, u16(response, 2))
        assertEquals(1, u16(response, 4))
        assertEquals(0, u16(response, 6))
        assertEquals(0, u16(response, 8))
        assertEquals(0, u16(response, 10))
        assertArrayEquals(parsed.question, response.copyOfRange(12, response.size))
    }

    @Test fun buildsAndParsesIpv4UdpWithCorrectAddressesPortsLengthsAndChecksums() {
        val source = Ipv4UdpPacket.address(10, 0, 0, 2)
        val destination = Ipv4UdpPacket.address(10, 0, 0, 1)
        val payload = query("example.com")
        val packet = Ipv4UdpPacket.build(source, destination, 53000, 53, payload)
        assertEquals(packet.size, u16(packet, 2))
        assertEquals(8 + payload.size, u16(packet, 24))
        assertEquals(0, internetChecksum(packet, 0, 20))
        assertEquals(0, udpChecksum(packet, source, destination))
        val parsed = requireNotNull(Ipv4UdpPacket.parse(packet))
        assertEquals(source, parsed.sourceAddress)
        assertEquals(destination, parsed.destinationAddress)
        assertEquals(53000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload)
        packet[9] = 6
        assertNull(Ipv4UdpPacket.parse(packet))
    }

    @Test fun buildsDnsResponseWithReversedAddressesPortsLengthsAndValidChecksums() {
        val client = Ipv4UdpPacket.address(10, 123, 0, 2)
        val dns = Ipv4UdpPacket.address(10, 123, 0, 1)
        val response = DnsMessage.buildNxdomainResponse(requireNotNull(DnsMessage.parseQuery(query("example.com"))))
        val packet = Ipv4UdpPacket.build(dns, client, 53, 53000, response)
        val parsed = requireNotNull(Ipv4UdpPacket.parse(packet))
        assertEquals(dns, parsed.sourceAddress)
        assertEquals(client, parsed.destinationAddress)
        assertEquals(53, parsed.sourcePort)
        assertEquals(53000, parsed.destinationPort)
        assertEquals(packet.size, u16(packet, 2))
        assertEquals(8 + response.size, u16(packet, 24))
        assertEquals(0, internetChecksum(packet, 0, 20))
        assertEquals(0, udpChecksum(packet, dns, client))
        assertArrayEquals(response, parsed.payload)
    }

    @Test fun rejectsEveryIpv4FragmentButAllowsDontFragment() {
        val packet = Ipv4UdpPacket.build(1, 2, 12345, 53, query("example.com"))
        writeU16(packet, 6, 0x2000)
        updateIpv4Checksum(packet)
        assertNull(Ipv4UdpPacket.parse(packet))

        writeU16(packet, 6, 0x4000)
        updateIpv4Checksum(packet)
        assertTrue(Ipv4UdpPacket.parse(packet) != null)
    }

    @Test fun rejectsBadIpChecksumAndNonDnsPayloadIsRejectedByDnsParser() {
        val packet = Ipv4UdpPacket.build(1, 2, 1, 53, byteArrayOf(1, 2, 3))
        packet[10] = (packet[10].toInt() xor 1).toByte()
        assertNull(Ipv4UdpPacket.parse(packet))
        assertNull(DnsMessage.parseQuery(byteArrayOf(1, 2, 3)))
    }

    private fun query(host: String): ByteArray {
        val labels = host.split('.')
        val bytes = ArrayList<Byte>()
        bytes += 0x12
        bytes += 0x34
        bytes += 0x01
        bytes += 0x00
        bytes += 0x00
        bytes += 0x01
        repeat(3) { bytes += 0; bytes += 0 }
        labels.forEach { label ->
            bytes += label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).forEach(bytes::add)
        }
        bytes += 0
        bytes += 0
        bytes += 1
        bytes += 0
        bytes += 1
        return bytes.toByteArray()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun updateIpv4Checksum(packet: ByteArray) {
        writeU16(packet, 10, 0)
        writeU16(packet, 10, internetChecksum(packet, 0, 20))
    }

    private fun udpChecksum(packet: ByteArray, source: Int, destination: Int): Int {
        val udpLength = u16(packet, 24)
        val pseudo = ByteArray(12 + udpLength)
        writeU32(pseudo, 0, source)
        writeU32(pseudo, 4, destination)
        pseudo[9] = 17
        writeU16(pseudo, 10, udpLength)
        packet.copyInto(pseudo, 12, 20, 20 + udpLength)
        return internetChecksum(pseudo, 0, pseudo.size)
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun internetChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((bytes[index].toInt() and 0xff) shl 8) or
                (bytes[index + 1].toInt() and 0xff)
            index += 2
        }
        if (index < end) sum += (bytes[index].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }
}
