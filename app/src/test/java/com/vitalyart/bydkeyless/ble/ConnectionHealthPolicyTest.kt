package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.BleConnectionState
import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthPolicyTest {
    @Test fun readyConnectionBecomesStaleWithoutBleActivity() {
        assertFalse(ConnectionHealthPolicy.isReadyConnectionStale(BleConnectionState.READY, 11_999, 0))
        assertTrue(ConnectionHealthPolicy.isReadyConnectionStale(BleConnectionState.READY, 12_000, 0))
        assertFalse(ConnectionHealthPolicy.isReadyConnectionStale(BleConnectionState.CONNECTING, 50_000, 0))
        assertFalse(ConnectionHealthPolicy.isReadyConnectionStale(BleConnectionState.READY, 50_000, Long.MIN_VALUE))
    }

    @Test fun reconnectBackoffIsBounded() {
        assertEquals(2_000L, ConnectionHealthPolicy.reconnectDelayMillis(1))
        assertEquals(4_000L, ConnectionHealthPolicy.reconnectDelayMillis(2))
        assertEquals(16_000L, ConnectionHealthPolicy.reconnectDelayMillis(4))
        assertEquals(30_000L, ConnectionHealthPolicy.reconnectDelayMillis(20))
    }
}
