package com.byd.aeri.projectCore.bluetooth.btkey.codec;

import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Java crypto/logging callbacks invoked from libJniBtLib. */
public final class Utils {
    private Utils() {}

    public static void RogD(String tag, String message) {}
    public static void RogE(String tag, String message) {}
    public static void RogI(String tag, String message) {}

    public static byte[] method1(byte[] data, byte[] key) {
        if (data == null || key == null) return null;
        try {
            Cipher cipher = Cipher.getInstance(BtJniUtils.getAlgorithm2_p1());
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, BtJniUtils.getAlgorithm2()));
            return cipher.doFinal(data);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static byte[] method3(byte[] data) {
        if (data == null) return null;
        try {
            return MessageDigest.getInstance(BtJniUtils.getAlgorithm1()).digest(data);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static byte[] method4(byte[] data, byte[] key, byte[] iv) {
        if (data == null || key == null) return null;
        try {
            String transformation = data.length == 16 ? BtJniUtils.getAlgorithm2_p2() : BtJniUtils.getAlgorithm2_p3();
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec keySpec = new SecretKeySpec(key, BtJniUtils.getAlgorithm2());
            if (iv == null) cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            else cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception ignored) {
            return null;
        }
    }
}
