package com.byd.aeri.projectCore.bluetooth.btkey.codec;

/** JNI surface retained with the exact package expected by BYD's native library. */
public final class BtJniUtils {
    static {
        System.loadLibrary("JniBtLib");
        System.loadLibrary("RSSILocation");
    }
    private BtJniUtils() {}

    public static native String getServiceUUID();
    public static native String getSendCharacteristicUUID();
    public static native String getReceiveCharacteristicUUID();
    public static native String getAlgorithm1();
    public static native String getAlgorithm2();
    public static native String getAlgorithm2_p1();
    public static native String getAlgorithm2_p2();
    public static native String getAlgorithm2_p3();
    public static native String getAlgorithm3();
    public static native byte[] createAppRndFrame(byte keyNumber);
    public static native byte[] createOverSeasAuthenticationFrame(String dkey, String vinRssi);
    public static native byte[] createCtrlCmdFrameWithDefaultUserInfo(byte command);
    public static native byte[] createCtrlCmdFrameWithDefaultUserInfoAndCmd7(byte command, byte value);
    public static native byte[] createMicroSwitchResponseFrame(byte state, byte result);
    public static native byte[] createWakeUpFrame();
    public static native byte[] createStartEngineNotToDriveFrame();
    public static native byte[] createEleUnlockDoorFrame(byte command);
    public static native byte[] createUwbCtrlCmdFrame(byte command);
    public static native byte[] createYunnianSceneFrame(byte command);
    public static native byte getCmdCtrlCode(int commandId);
    public static native byte getCtrlCodeLock();
    public static native byte getCtrlCodeUnlockFourDoors();
    public static native byte getCtrlCodeUnlockRearDoor();
    public static native byte getCtrlCodeSearchAuto();
    public static native byte getCtrlCodeFlashLight();
    public static native byte getCtrlCodeRiseAutoWindow();
    public static native byte getCtrlTurnOnAC();
    public static native byte getCtrlTurnOffAC();
    public static native byte getCtrlCodeOpenEleRearDoor();
    public static native byte getCtrlCodeCloseEleRearDoor();
    public static native byte getCtrlCodePendingEleRearDoor();
    public static native byte getCtrlCodePhoneUwbLocation();
    public static native byte getCtrlCodeRPAUnlockCar();
    public static native byte getCtrlCodeAutoGoForward();
    public static native byte getCtrlCodeAutoGoBackward();
    public static native byte getCtrlCodeAutoTurnLeft();
    public static native byte getCtrlCodeAutoTurnRight();
    public static native byte getCtrlCodeYunnianScene();
    public static native byte parseAuthResultFrame(byte[] frame);
    public static native byte parseAuthRangingResultFrame(byte[] frame);
    public static native int isUserInfoAuthPass(byte[] frame);
    public static native byte parseCtrlCmdResult(byte[] frame);
    public static native byte parseDoorStates(byte[] frame);
    public static native byte parseRespTypeCodeResult(byte[] frame);
    public static native byte getBtResponseCodeCtrlCmd();
    public static native byte getBtResponseCodeNewBtKeyAuth();
    public static native byte getBtResponseCodeExchangeRandom();
    public static native byte[] parseDataInfoFromRecvFrameWithCheckCrc(byte[] frame, Integer result);
    public static native boolean parseVehicleRndFrame(
            com.byd.aeri.projectCore.bluetooth.bean.RandomExchangeCmdResultResp result,
            byte[] frame
    );
    public static native int getBleTransitionProtocol();
    public static native byte setBluetoothVersion(int version);
    public static native byte getBtResponseTypeMicroSwitch();
    public static native byte getSmallSwitchPressed();
    public static native int processrssi2area(int rssi, Long timestamp, int calibration);
    public static native String getRssiAlgorithmVersion();
}
