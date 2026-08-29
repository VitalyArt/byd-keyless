package com.sign.overseas;

/**
 * Compatibility surface required by libJniBtLib. The Wear implementation compares the
 * host APK certificate with BYD's distribution certificate, which cannot match a personal
 * sideload build. TLS, watch-token and dkey authentication remain independent of this check.
 */
public final class SignCheck {
    private String realCer;

    public SignCheck() {}

    public SignCheck(String expectedCertificate) {
        this.realCer = expectedCertificate;
    }

    public boolean check() {
        return true;
    }

    public String getCertificateSHA1Fingerprint() {
        return realCer;
    }

    public String getRealCer() {
        return realCer;
    }

    public void setRealCer(String certificate) {
        this.realCer = certificate;
    }
}
