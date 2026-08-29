package com.vitalyart.bydkeyless.ble

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vitalyart.bydkeyless.model.VehicleCommand
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    @Test fun loadsBydLibrariesAndResolvesSafeSymbols() {
        val native = BydNativeFacade()
        assertTrue(native.loaded)
        assertNotNull(native.serviceUuid())
        assertNotNull(native.sendUuid())
        assertNotNull(native.receiveUuid())
        assertNotNull(native.rssiAlgorithmVersion())
        assertTrue(native.commandFrame(VehicleCommand.LOCK)?.isNotEmpty() == true)
        assertTrue(native.commandFrame(VehicleCommand.UNLOCK)?.isNotEmpty() == true)
        assertTrue(native.commandFrame(VehicleCommand.OPEN_TRUNK)?.isNotEmpty() == true)
        assertTrue(native.commandFrame(VehicleCommand.CLOSE_TRUNK)?.isNotEmpty() == true)
        assertTrue(native.commandFrame(VehicleCommand.OPEN_TRUNK)?.contentEquals(native.commandFrame(VehicleCommand.CLOSE_TRUNK)) == false)
        assertTrue(native.authenticationFrame("00000000000000000000000000000000", "FFFFFFFFFFFFFFFFFFFFFF").isNotEmpty())
        assertTrue(native.randomExchangeFrame(1).isNotEmpty())
    }
}
