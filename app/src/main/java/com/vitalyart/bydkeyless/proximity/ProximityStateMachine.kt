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
    private var lastSampleAt: Long? = null
    private var stableZone = ProximityZone.UNKNOWN
    private var unlockedThisApproach = false
    private var wasNear = false
    private var suppressUnlockUntilFar = false
    private var uncertainOutcome = false
    private var pending = false
    private var attempts = 0
    private var retryAt = 0L

    fun configure(mode: KeylessMode, calibration: ProximityCalibration?, autoUnlock: Boolean, autoLock: Boolean) {
        if (this.mode != mode || this.calibration != calibration || this.autoUnlock != autoUnlock || this.autoLock != autoLock) resetSignal()
        this.mode = mode; this.calibration = calibration; this.autoUnlock = autoUnlock; this.autoLock = autoLock
        if (mode == KeylessMode.OFF) {
            unlockedThisApproach = false; wasNear = false; suppressUnlockUntilFar = false
            pending = false; uncertainOutcome = false
        }
    }

    fun sample(rssi: Int, closuresClosed: Boolean?, now: Long, canUnlock: Boolean, canLock: Boolean): ProximityDecision {
        if (mode != KeylessMode.AUTO_UNLOCK_LOCK || rssi !in -127..-1) return decision()
        lastSampleAt?.let { if (now < it || now - it > MAX_SAMPLE_GAP_MS) resetSignal() }
        lastSampleAt = now
        smooth = smooth?.let { it * .75 + rssi * .25 } ?: rssi.toDouble()
        val cfg = calibration ?: return decision()
        val next = when {
            smooth!! >= cfg.unlockThreshold -> ProximityZone.NEAR
            smooth!! <= cfg.lockThreshold -> ProximityZone.FAR
            else -> ProximityZone.APPROACHING
        }
        if (candidate != next) { candidate = next; candidateSince = now; return decision() }
        val required = if (next == ProximityZone.FAR) 10_000L else 3_000L
        if (now - candidateSince < required) return decision()
        if (stableZone != next) {
            stableZone = next
            attempts = 0
            retryAt = 0L
            if (next == ProximityZone.NEAR) wasNear = true
            if (next == ProximityZone.FAR) {
                suppressUnlockUntilFar = false
                if (!autoLock) unlockedThisApproach = false
            }
        }
        if (uncertainOutcome || pending || attempts >= 2 || now < retryAt) return decision()
        val unlock = next == ProximityZone.NEAR && autoUnlock && !unlockedThisApproach && !suppressUnlockUntilFar && canUnlock
        val lock = next == ProximityZone.FAR && autoLock && wasNear && unlockedThisApproach && canLock && closuresClosed == true
        if (unlock || lock) { pending = true; attempts += 1 }
        return decision(unlock, lock)
    }

    fun markUnlocked() { attempts = 0; retryAt = 0L; uncertainOutcome = false; pending = false; unlockedThisApproach = true; wasNear = true }
    fun markLocked() {
        attempts = 0; retryAt = 0L
        uncertainOutcome = false; pending = false; unlockedThisApproach = false; wasNear = false
        suppressUnlockUntilFar = stableZone != ProximityZone.FAR
    }

    /** Retry only a definitely rejected request; an unknown physical outcome needs reconciliation. */
    fun commandFailed(now: Long, uncertain: Boolean) {
        pending = false
        retryAt = now + 5_000L
        if (uncertain) {
            uncertainOutcome = true
            attempts = 2
            suppressUnlockUntilFar = true
        }
    }

    fun state(): ProximityState = ProximityState(stableZone, smooth, !uncertainOutcome && !unlockedThisApproach && !suppressUnlockUntilFar, needsConfirmation = uncertainOutcome)
    private fun decision(requestUnlock: Boolean = false, requestLock: Boolean = false) = ProximityDecision(state(), requestUnlock, requestLock)

    fun resetSignal() {
        smooth = null; candidate = null; candidateSince = 0L; stableZone = ProximityZone.UNKNOWN; lastSampleAt = null
        // Preserve approach/command history through a radio interruption.
    }

    companion object { const val MAX_SAMPLE_GAP_MS = 4_000L }
}
