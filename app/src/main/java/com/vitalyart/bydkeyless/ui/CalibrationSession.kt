package com.vitalyart.bydkeyless.ui

import kotlinx.coroutines.flow.first

/** Pure measurement accumulator: never accepts cached or cross-connection samples. */
class CalibrationSamples(private val generation: Long, private val initialSequence: Long) {
    private val values = mutableListOf<Int>()
    private var lastSequence = initialSequence
    val count get() = values.size
    fun add(generation: Long, sequence: Long, ageMillis: Long, rssi: Int?): Boolean {
        if (generation != this.generation || sequence <= lastSequence || ageMillis !in 0..4_000 || rssi == null || rssi !in -127..-1 || count == 8) return false
        lastSequence = sequence
        values += rssi
        return true
    }
    fun median(): Int? = if (count != 8 || values.max() - values.min() > 12) null
        else values.sorted().let { (it[3] + it[4]) / 2 }
}

enum class CalibrationStep { CLOSED, PREPARE, OPEN, CLOSE, REVIEW, SAVED, TEST }
data class CalibrationSession(
    val step: CalibrationStep = CalibrationStep.CLOSED,
    val onlyOpen: Boolean? = null,
    val open: Double? = null,
    val close: Double? = null,
    val measuring: Boolean = false,
    val samples: Int = 0,
    val error: UiMessage? = null,
)

/** Sampling lifetime is owned by the caller's job; cancellation cannot publish a result. */
suspend fun measureCalibration(
    telemetry: kotlinx.coroutines.flow.StateFlow<com.vitalyart.bydkeyless.model.VehicleTelemetry>,
    connection: kotlinx.coroutines.flow.StateFlow<com.vitalyart.bydkeyless.model.BleConnectionState>,
    clock: () -> Long,
    progress: (Int) -> Unit,
): Int? {
    val initial = telemetry.value
    val samples = CalibrationSamples(initial.connectionGeneration, initial.rssiSequence)
    val completed = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
        kotlinx.coroutines.flow.combine(telemetry, connection) { value, state -> value to state }
            .first { (value, state) ->
                if (state != com.vitalyart.bydkeyless.model.BleConnectionState.READY || value.connectionGeneration != initial.connectionGeneration) return@first true
                samples.add(value.connectionGeneration, value.rssiSequence,
                    value.rssiAtMillis?.let { clock() - it } ?: Long.MAX_VALUE, value.rssi)
                progress(samples.count)
                samples.count == 8
            }
        samples.count == 8
    } == true
    return if (completed) samples.median() else null
}
