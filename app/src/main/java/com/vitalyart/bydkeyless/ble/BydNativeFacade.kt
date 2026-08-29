package com.vitalyart.bydkeyless.ble

import com.byd.aeri.projectCore.bluetooth.btkey.codec.BtJniUtils
import com.byd.aeri.projectCore.bluetooth.bean.RandomExchangeCmdResultResp
import com.vitalyart.bydkeyless.model.VehicleCommand
import java.util.UUID

class BydNativeFacade {
    data class CommandAck(val success: Boolean, val allClosuresClosed: Boolean)

    val loaded: Boolean by lazy { runCatching { BtJniUtils.getServiceUUID(); true }.getOrDefault(false) }

    fun serviceUuid(): UUID = UUID.fromString(BtJniUtils.getServiceUUID())
    fun sendUuid(): UUID = UUID.fromString(BtJniUtils.getSendCharacteristicUUID())
    fun receiveUuid(): UUID = UUID.fromString(BtJniUtils.getReceiveCharacteristicUUID())
    fun authenticationFrame(dkey: String, vinRssi: String): ByteArray = BtJniUtils.createOverSeasAuthenticationFrame(dkey, vinRssi)
    fun randomExchangeFrame(keyNumber: Long): ByteArray {
        BtJniUtils.setBluetoothVersion(BtJniUtils.getBleTransitionProtocol())
        return BtJniUtils.createAppRndFrame(keyNumber.toByte())
    }
    fun decodeIncomingFrame(frame: ByteArray): ByteArray? = runCatching {
        BtJniUtils.parseDataInfoFromRecvFrameWithCheckCrc(frame, 1)
    }.getOrNull()
    fun isRandomExchangeResponse(frame: ByteArray): Boolean = runCatching {
        BtJniUtils.parseRespTypeCodeResult(frame) == BtJniUtils.getBtResponseCodeExchangeRandom()
    }.getOrDefault(false)
    fun randomExchangePassed(frame: ByteArray): Boolean = runCatching {
        val result = RandomExchangeCmdResultResp()
        BtJniUtils.parseVehicleRndFrame(result, frame) &&
            result.m3946b().toInt() == 1 && result.m3947c().toInt() == 1
    }.getOrDefault(false)
    fun authenticationPassed(frame: ByteArray): Boolean = runCatching {
        BtJniUtils.parseRespTypeCodeResult(frame) == BtJniUtils.getBtResponseCodeNewBtKeyAuth() &&
            BtJniUtils.parseAuthResultFrame(frame).toInt() == 1
    }.getOrDefault(false)
    fun commandResponse(frame: ByteArray): CommandAck? = runCatching {
        if (BtJniUtils.parseRespTypeCodeResult(frame) != BtJniUtils.getBtResponseCodeCtrlCmd()) return@runCatching null
        CommandAck(
            success = BtJniUtils.parseCtrlCmdResult(frame).toInt() == 1,
            allClosuresClosed = (BtJniUtils.parseDoorStates(frame).toInt() and 0x0f) == 0,
        )
    }.getOrNull()
    fun rssiArea(rssi: Int, calibration: Int = 0): Int = runCatching {
        BtJniUtils.processrssi2area(rssi, System.currentTimeMillis(), calibration)
    }.getOrDefault(-1)
    fun rssiAlgorithmVersion(): String? = runCatching { BtJniUtils.getRssiAlgorithmVersion() }.getOrNull()

    fun isMicroSwitchPressed(frame: ByteArray): Boolean = runCatching {
        BtJniUtils.parseRespTypeCodeResult(frame) == BtJniUtils.getBtResponseTypeMicroSwitch() &&
            frame.isNotEmpty()
    }.getOrDefault(false)
    fun microSwitchResponse(): ByteArray = BtJniUtils.createMicroSwitchResponseFrame(BtJniUtils.getSmallSwitchPressed(), 1)

    fun commandFrame(command: VehicleCommand): ByteArray? = runCatching {
        when (command) {
            VehicleCommand.LOCK -> control(BtJniUtils.getCtrlCodeLock())
            VehicleCommand.UNLOCK -> control7(BtJniUtils.getCtrlCodeUnlockFourDoors(), 0)
            VehicleCommand.FIND_CAR -> control(BtJniUtils.getCtrlCodeSearchAuto())
            VehicleCommand.OPEN_TRUNK -> control(BtJniUtils.getCtrlCodeUnlockRearDoor())
            // The powered tailgate is not a regular 9011 control frame. BYD exposes
            // a dedicated electric-rear-door frame family for close/pause/open.
            VehicleCommand.CLOSE_TRUNK -> BtJniUtils.createEleUnlockDoorFrame(BtJniUtils.getCtrlCodeCloseEleRearDoor())
            VehicleCommand.CLOSE_WINDOWS -> control(BtJniUtils.getCtrlCodeRiseAutoWindow())
            VehicleCommand.FLASH_LIGHT -> control(BtJniUtils.getCtrlCodeFlashLight())
            VehicleCommand.TURN_ON_AC -> control(BtJniUtils.getCtrlTurnOnAC())
            VehicleCommand.TURN_OFF_AC -> control(BtJniUtils.getCtrlTurnOffAC())
            VehicleCommand.START_ENGINE -> BtJniUtils.createStartEngineNotToDriveFrame()
            VehicleCommand.STOP_ENGINE -> control7(BtJniUtils.getCmdCtrlCode(command.nativeId), -1)
            VehicleCommand.OPEN_ELECTRIC_REAR_DOOR -> BtJniUtils.createEleUnlockDoorFrame(BtJniUtils.getCtrlCodeOpenEleRearDoor())
            VehicleCommand.CLOSE_ELECTRIC_REAR_DOOR -> BtJniUtils.createEleUnlockDoorFrame(BtJniUtils.getCtrlCodeCloseEleRearDoor())
            VehicleCommand.PAUSE_ELECTRIC_REAR_DOOR -> BtJniUtils.createEleUnlockDoorFrame(BtJniUtils.getCtrlCodePendingEleRearDoor())
            VehicleCommand.UWB_LOCATE -> BtJniUtils.createUwbCtrlCmdFrame(BtJniUtils.getCtrlCodePhoneUwbLocation())
            VehicleCommand.RPA_UNLOCK -> control(BtJniUtils.getCtrlCodeRPAUnlockCar())
            VehicleCommand.RPA_FORWARD -> control(BtJniUtils.getCtrlCodeAutoGoForward())
            VehicleCommand.RPA_BACKWARD -> control(BtJniUtils.getCtrlCodeAutoGoBackward())
            VehicleCommand.RPA_LEFT -> control(BtJniUtils.getCtrlCodeAutoTurnLeft())
            VehicleCommand.RPA_RIGHT -> control(BtJniUtils.getCtrlCodeAutoTurnRight())
            VehicleCommand.YUNNIAN_SCENE -> BtJniUtils.createYunnianSceneFrame(BtJniUtils.getCtrlCodeYunnianScene())
        }
    }.getOrNull()

    private fun control(code: Byte) = BtJniUtils.createCtrlCmdFrameWithDefaultUserInfo(code)
    private fun control7(code: Byte, value: Int) = BtJniUtils.createCtrlCmdFrameWithDefaultUserInfoAndCmd7(code, value.toByte())
}
