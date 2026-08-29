package com.vitalyart.bydkeyless.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionStateResolverTest {
    @Test fun distinguishesFirstDenialPermanentDenialAndBluetooth() {
        assertEquals(PermissionState.DENIED, PermissionStateResolver.resolve(false, false, true, true))
        assertEquals(PermissionState.PERMANENTLY_DENIED, PermissionStateResolver.resolve(false, true, true, true))
        assertEquals(PermissionState.BLUETOOTH_OFF, PermissionStateResolver.resolve(true, true, false, false))
        assertEquals(PermissionState.GRANTED, PermissionStateResolver.resolve(true, true, false, true))
    }
}
