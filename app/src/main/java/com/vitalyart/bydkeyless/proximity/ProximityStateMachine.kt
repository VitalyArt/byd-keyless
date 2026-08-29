package com.vitalyart.bydkeyless.proximity

import com.vitalyart.bydkeyless.model.*

data class ProximityDecision(val state: ProximityState, val requestUnlock: Boolean = false, val requestLock: Boolean = false)

class ProximityStateMachine {
    private var mode = KeylessMode.OFF
    private var calibration: ProximityCalibration? = null
    private var autoUnlock = false
    private var autoLock = false
    private var smooth: Double? = null
    private var candidate: ProximityZone? = null
    private var candidateSince = 0L
    private var stableZone = ProximityZone.UNKNOWN
    private var unlockedThisApproach = false
    private var wasNear = false

    fun configure(mode: KeylessMode, calibration: ProximityCalibration?, autoUnlock: Boolean, autoLock: Boolean) {
        this.mode = mode; this.calibration = calibration; this.autoUnlock = autoUnlock; this.autoLock = autoLock
        if (mode == KeylessMode.OFF) reset()
    }

    fun sample(rssi: Int, closuresClosed: Boolean?, now: Long, canUnlock: Boolean, canLock: Boolean): ProximityDecision {
        smooth = smooth?.let { it * .75 + rssi * .25 } ?: rssi.toDouble()
        val cfg = calibration ?: return decision()
        val next = when {
            smooth!! >= cfg.unlockThreshold -> ProximityZone.NEAR
            smooth!! <= cfg.lockThreshold -> ProximityZone.FAR
            else -> ProximityZone.APPROACHING
        }
        if (candidate != next) { candidate = next; candidateSince = now; return decision() }
        val required = if (next == ProximityZone.FAR) 10_000L else 3_000L
        if (now - candidateSince < required || stableZone == next) return decision()
        stableZone = next
        if (next == ProximityZone.NEAR) {
            wasNear = true
            return decision(requestUnlock = mode == KeylessMode.AUTO_UNLOCK_LOCK && autoUnlock && !unlockedThisApproach && canUnlock)
        }
        if (next == ProximityZone.FAR) {
            return decision(requestLock = mode == KeylessMode.AUTO_UNLOCK_LOCK && autoLock && wasNear && unlockedThisApproach && canLock && closuresClosed == true)
        }
        return decision()
    }

    fun markUnlocked() { unlockedThisApproach = true }
    fun markLocked() { unlockedThisApproach = false; wasNear = false }

    private fun decision(requestUnlock: Boolean = false, requestLock: Boolean = false) =
        ProximityDecision(ProximityState(stableZone, smooth, !unlockedThisApproach), requestUnlock, requestLock)

    private fun reset() {
        smooth = null; candidate = null; candidateSince = 0L; stableZone = ProximityZone.UNKNOWN
        unlockedThisApproach = false; wasNear = false
    }
}
