package com.vitalyart.bydkeyless.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleScopePolicyTest {
    @Test fun resetsOnlyWhenKnownVinChanges() {
        assertFalse(VehicleScopePolicy.shouldReset(null, "VIN-A"))
        assertFalse(VehicleScopePolicy.shouldReset("VIN-A", "VIN-A"))
        assertTrue(VehicleScopePolicy.shouldReset("VIN-A", "VIN-B"))
    }
}
