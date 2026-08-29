package com.vitalyart.bydkeyless.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ResilientWatchDnsTest {
    @Test fun reusesLastSuccessfulAddressAfterTransientFailure() {
        val expected = listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)))
        var available = true
        val system = dns { if (available) expected else throw UnknownHostException("transient") }
        val dns = ResilientWatchDns(system, emptyMap())
        assertEquals(expected, dns.lookup("example.test"))
        available = false
        assertEquals(expected, dns.lookup("example.test"))
    }

    @Test fun usesBootstrapOnlyWhenSystemHasNoAnswer() {
        val expected = listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)))
        val dns = ResilientWatchDns(dns { throw UnknownHostException("missing") }, mapOf("byd.test" to expected))
        assertEquals(expected, dns.lookup("byd.test"))
    }

    private fun dns(block: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = block(hostname)
    }
}
