package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.BleConnectionState
import kotlin.math.min

object ConnectionHealthPolicy {
    const val READY_STALE_TIMEOUT_MS = 12_000L
    const val MAX_RECONNECT_DELAY_MS = 30_000L

    fun isReadyConnectionStale(state: BleConnectionState, now: Long, lastActivityAt: Long): Boolean =
        state == BleConnectionState.READY && lastActivityAt != Long.MIN_VALUE &&
            now - lastActivityAt >= READY_STALE_TIMEOUT_MS

    fun reconnectDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 4)
        return min(2_000L shl exponent, MAX_RECONNECT_DELAY_MS)
    }
}
