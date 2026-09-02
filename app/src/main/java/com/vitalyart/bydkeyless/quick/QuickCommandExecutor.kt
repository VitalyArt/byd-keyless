package com.vitalyart.bydkeyless.quick

import com.vitalyart.bydkeyless.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

enum class QuickCommandPhase { IDLE, CONNECTING, EXECUTING, SUCCESS, ERROR }

data class QuickCommandState(
    val phase: QuickCommandPhase = QuickCommandPhase.IDLE,
    val command: VehicleCommand? = null,
    val error: CommandError? = null,
)

object QuickCommandContract {
    const val ACTION_LOCK = "com.vitalyart.bydkeyless.quick.LOCK"
    const val ACTION_UNLOCK = "com.vitalyart.bydkeyless.quick.UNLOCK"
    const val ACTION_OPEN_TRUNK = "com.vitalyart.bydkeyless.quick.OPEN_TRUNK"
    val supportedCommands = setOf(VehicleCommand.LOCK, VehicleCommand.UNLOCK, VehicleCommand.OPEN_TRUNK)

    fun commandForAction(action: String?): VehicleCommand? = when (action) {
        ACTION_LOCK -> VehicleCommand.LOCK
        ACTION_UNLOCK -> VehicleCommand.UNLOCK
        ACTION_OPEN_TRUNK -> VehicleCommand.OPEN_TRUNK
        else -> null
    }

    fun actionFor(command: VehicleCommand): String? = when (command) {
        VehicleCommand.LOCK -> ACTION_LOCK
        VehicleCommand.UNLOCK -> ACTION_UNLOCK
        VehicleCommand.OPEN_TRUNK -> ACTION_OPEN_TRUNK
        else -> null
    }

    fun contextualDoorCommand(state: DoorState): VehicleCommand? = when (state) {
        DoorState.LOCKED -> VehicleCommand.UNLOCK
        DoorState.UNLOCKED -> VehicleCommand.LOCK
        DoorState.UNKNOWN -> null
    }
}

class QuickCommandExecutor(
    private val sessionProvider: () -> Pair<WatchToken, VehicleProfile>?,
    private val controller: BleVehicleController,
    private val preflight: () -> CommandError?,
    private val connectionTimeoutMillis: Long = 20_000L,
) {
    private val commandMutex = Mutex()
    private val _state = MutableStateFlow(QuickCommandState())
    val state: StateFlow<QuickCommandState> = _state.asStateFlow()

    fun clearTerminalState(expected: QuickCommandState): Boolean {
        if (_state.value == expected && expected.phase in setOf(QuickCommandPhase.SUCCESS, QuickCommandPhase.ERROR)) {
            _state.value = QuickCommandState()
            return true
        }
        return false
    }

    suspend fun execute(command: VehicleCommand): CommandResult {
        if (command !in QuickCommandContract.supportedCommands) {
            return CommandResult.Rejected(CommandError.CAPABILITY_NOT_CONFIRMED)
        }
        if (!commandMutex.tryLock()) return CommandResult.Rejected(CommandError.COMMAND_BUSY)
        try {
            val session = sessionProvider() ?: return fail(command, CommandError.NO_SESSION)
            val profile = session.second
            if (!profile.hasValidKey()) return fail(command, CommandError.KEY_EXPIRED)
            if (command.functionCode == null || command.functionCode !in profile.capabilities) {
                return fail(command, CommandError.CAPABILITY_NOT_CONFIRMED)
            }
            preflight()?.let { return fail(command, it) }

            if (controller.connectionState.value != BleConnectionState.READY) {
                _state.value = QuickCommandState(QuickCommandPhase.CONNECTING, command)
                controller.connect(profile)
                val terminal = withTimeoutOrNull(connectionTimeoutMillis) {
                    controller.connectionState.first { it == BleConnectionState.READY || it == BleConnectionState.ERROR }
                }
                if (terminal != BleConnectionState.READY) return fail(command, CommandError.CONNECTION_TIMEOUT)
            }

            _state.value = QuickCommandState(QuickCommandPhase.EXECUTING, command)
            val result = controller.execute(command)
            _state.value = when (result) {
                CommandResult.Success -> QuickCommandState(QuickCommandPhase.SUCCESS, command)
                is CommandResult.Rejected -> QuickCommandState(QuickCommandPhase.ERROR, command, result.error)
                is CommandResult.Failure -> QuickCommandState(QuickCommandPhase.ERROR, command, result.error)
            }
            return result
        } finally {
            commandMutex.unlock()
        }
    }

    private fun fail(command: VehicleCommand, error: CommandError): CommandResult.Rejected {
        _state.value = QuickCommandState(QuickCommandPhase.ERROR, command, error)
        return CommandResult.Rejected(error)
    }
}
