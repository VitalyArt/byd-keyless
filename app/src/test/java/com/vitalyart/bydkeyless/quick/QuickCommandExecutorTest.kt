package com.vitalyart.bydkeyless.quick

import com.vitalyart.bydkeyless.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class QuickCommandExecutorTest {
    private val profile = VehicleProfile(
        vin = "TESTVIN",
        macAddress = "00:00:00:00:00:00",
        digitalKey = "key",
        keyNumber = 1,
        keyValidTo = null,
        capabilities = setOf("1005", "1006", "1020"),
    )
    private val token = WatchToken(1, "user", "imei", "enc", "sign", 1, "en", "TESTVIN", "owner", null)

    @Test fun routesOnlyFixedQuickActions() {
        assertEquals(VehicleCommand.LOCK, QuickCommandContract.commandForAction(QuickCommandContract.ACTION_LOCK))
        assertEquals(VehicleCommand.UNLOCK, QuickCommandContract.commandForAction(QuickCommandContract.ACTION_UNLOCK))
        assertEquals(VehicleCommand.OPEN_TRUNK, QuickCommandContract.commandForAction(QuickCommandContract.ACTION_OPEN_TRUNK))
        assertNull(QuickCommandContract.commandForAction("arbitrary"))
    }

    @Test fun choosesContextualDoorActionOnlyForKnownState() {
        assertEquals(VehicleCommand.UNLOCK, QuickCommandContract.contextualDoorCommand(DoorState.LOCKED))
        assertEquals(VehicleCommand.LOCK, QuickCommandContract.contextualDoorCommand(DoorState.UNLOCKED))
        assertNull(QuickCommandContract.contextualDoorCommand(DoorState.UNKNOWN))
    }

    @Test fun connectsBeforeExecuting() = runTest {
        val controller = FakeController(connects = true)
        val executor = executor(controller)

        assertEquals(CommandResult.Success, executor.execute(VehicleCommand.UNLOCK))
        assertEquals(1, controller.connectCount)
        assertEquals(listOf(VehicleCommand.UNLOCK), controller.executed)
        assertEquals(QuickCommandPhase.SUCCESS, executor.state.value.phase)
    }

    @Test fun timesOutWithoutSendingCommand() = runTest {
        val controller = FakeController(connects = false)
        val executor = executor(controller, timeout = 100)

        assertEquals(CommandResult.Rejected(CommandError.CONNECTION_TIMEOUT), executor.execute(VehicleCommand.LOCK))
        assertTrue(controller.executed.isEmpty())
    }

    @Test fun rejectsParallelCommand() = runTest {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val controller = FakeController(connects = true, executeBlock = {
            entered.complete(Unit)
            release.await()
        })
        val executor = executor(controller)

        val first = async { executor.execute(VehicleCommand.UNLOCK) }
        entered.await()
        assertEquals(CommandResult.Rejected(CommandError.COMMAND_BUSY), executor.execute(VehicleCommand.LOCK))
        release.complete(Unit)
        assertEquals(CommandResult.Success, first.await())
    }

    @Test fun rejectsMissingCapabilityBeforeConnecting() = runTest {
        val controller = FakeController(connects = true)
        val limited = profile.copy(capabilities = setOf("1005"))
        val executor = QuickCommandExecutor({ token to limited }, controller, { null }, 100)

        assertEquals(CommandResult.Rejected(CommandError.CAPABILITY_NOT_CONFIRMED), executor.execute(VehicleCommand.OPEN_TRUNK))
        assertEquals(0, controller.connectCount)
    }

    private fun executor(controller: FakeController, timeout: Long = 1_000) =
        QuickCommandExecutor({ token to profile }, controller, { null }, timeout)

    private class FakeController(
        private val connects: Boolean,
        private val executeBlock: suspend () -> Unit = {},
    ) : BleVehicleController {
        private val mutableState = MutableStateFlow(BleConnectionState.IDLE)
        override val connectionState: StateFlow<BleConnectionState> = mutableState
        override val telemetry: StateFlow<VehicleTelemetry> = MutableStateFlow(VehicleTelemetry())
        var connectCount = 0
        val executed = mutableListOf<VehicleCommand>()

        override suspend fun connect(profile: VehicleProfile) {
            connectCount++
            mutableState.value = if (connects) BleConnectionState.READY else BleConnectionState.CONNECTING
        }

        override suspend fun disconnect() { mutableState.value = BleConnectionState.IDLE }

        override suspend fun execute(command: VehicleCommand, experimentalEnabled: Boolean): CommandResult {
            executed += command
            executeBlock()
            return CommandResult.Success
        }
    }
}
