package com.vitalyart.bydkeyless.hotspot

import com.vitalyart.bydkeyless.model.BleConnectionState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HotspotAutomationManagerTest {
    @Test fun enablesOnReadyAndDisablesAfterConfiguredDelay() = runTest {
        var settings = HotspotAutomationSettings(true, true, 60_000L)
        val controller = RecordingController()
        val manager = HotspotAutomationManager({ settings }, controller, backgroundScope)

        manager.onConnectionStateChanged(BleConnectionState.READY)
        runCurrent()
        assertEquals(listOf(true), controller.changes)

        manager.onConnectionStateChanged(BleConnectionState.DISCONNECTED)
        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals(listOf(true), controller.changes)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(true, false), controller.changes)
    }

    @Test fun reconnectCancelsPendingDisable() = runTest {
        val controller = RecordingController()
        val manager = HotspotAutomationManager(
            { HotspotAutomationSettings(true, true, 30_000L) }, controller, backgroundScope,
        )

        manager.onConnectionStateChanged(BleConnectionState.READY)
        runCurrent()
        manager.onConnectionStateChanged(BleConnectionState.DISCONNECTED)
        advanceTimeBy(10_000L)
        manager.onConnectionStateChanged(BleConnectionState.READY)
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(listOf(true), controller.changes)
    }

    @Test fun zeroDelayDisablesImmediately() = runTest {
        val controller = RecordingController()
        val manager = HotspotAutomationManager(
            { HotspotAutomationSettings(true, true, 0L) }, controller, backgroundScope,
        )

        manager.onConnectionStateChanged(BleConnectionState.READY)
        runCurrent()
        manager.onConnectionStateChanged(BleConnectionState.DISCONNECTED)
        runCurrent()

        assertEquals(listOf(true, false), controller.changes)
    }

    private class RecordingController : HotspotController {
        val changes = mutableListOf<Boolean>()
        override suspend fun setEnabled(enabled: Boolean): Boolean {
            changes += enabled
            return true
        }
    }
}
