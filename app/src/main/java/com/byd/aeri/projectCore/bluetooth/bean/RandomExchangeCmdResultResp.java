package com.byd.aeri.projectCore.bluetooth.bean;

/** Compatibility DTO populated by libJniBtLib while parsing random exchange. */
public final class RandomExchangeCmdResultResp {
    boolean f2987a;
    private byte[] mCarRandomData;
    private byte mCrc8CheckResult = -1;
    private byte mKeyState = -1;

    public byte[] m3945a() { return mCarRandomData; }
    public byte m3946b() { return mCrc8CheckResult; }
    public byte m3947c() { return mKeyState; }
    public boolean m3948d() { return f2987a; }
    public void m3949e(byte[] value) { mCarRandomData = value; }
    public void m3950f(byte value) { mCrc8CheckResult = value; }
    public void m3951g(byte value) { mKeyState = value; }
    public void m3952h(boolean value) { f2987a = value; }
}
