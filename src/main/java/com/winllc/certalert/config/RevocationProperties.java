package com.winllc.certalert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The revocation check, bound from {@code cert-alert.revocation}.
 *
 * <p>Two mechanisms, and which one is right depends entirely on how many certificates are
 * being asked about. A CRL is one download that answers for every certificate an authority
 * ever issued, so it is what the scheduled job uses on a directory of a hundred thousand
 * entries. OCSP is one request per certificate and a fresher answer, so it is what a single
 * check asks with - and what it needs, an issuer certificate, is exactly what a CRL check
 * can do without.
 */
@ConfigurationProperties(prefix = "cert-alert.revocation")
public class RevocationProperties {

    /** Whether revocation is checked at all. */
    private boolean enabled = true;

    /** When the scheduled check runs, after both sweeps have refreshed the cache. */
    private String cron = "0 30 5 * * *";

    /**
     * A directory of CA certificates, PEM or DER, one certificate per file.
     *
     * <p>Two things need them. OCSP identifies a certificate by hashes of its issuer's name
     * and key, which are computed from the issuer's certificate and cannot be had any other
     * way. And a CRL is signed by the issuer, so verifying that the list is the authority's
     * own rather than whatever answered the URL needs the same certificate.
     *
     * <p>Empty means neither is possible: OCSP is skipped, and CRLs are used unverified
     * where {@link #isAllowUnverifiedCrl()} permits it.
     */
    private String issuerDirectory;

    /**
     * Whether a CRL whose signature could not be verified may still be believed.
     *
     * <p>On by default, because the alternative on a deployment with no issuer certificates
     * configured is that nothing is ever checked - and a CRL fetched from the URL the
     * certificate itself names, over a network the operator controls, is worth more than no
     * answer at all. Every such result says so in its detail. Turn it off where the answer
     * has to be provably the authority's.
     */
    private boolean allowUnverifiedCrl = true;

    /** How long a fetched CRL is reused before being fetched again, at the most. */
    private Duration crlCacheTtl = Duration.ofHours(6);

    /** A CRL larger than this is refused rather than read into memory. */
    private int maxCrlBytes = 16 * 1024 * 1024;

    private Duration connectTimeout = Duration.ofSeconds(10);

    private Duration readTimeout = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getIssuerDirectory() {
        return issuerDirectory;
    }

    public void setIssuerDirectory(String issuerDirectory) {
        this.issuerDirectory = issuerDirectory;
    }

    public boolean isAllowUnverifiedCrl() {
        return allowUnverifiedCrl;
    }

    public void setAllowUnverifiedCrl(boolean allowUnverifiedCrl) {
        this.allowUnverifiedCrl = allowUnverifiedCrl;
    }

    public Duration getCrlCacheTtl() {
        return crlCacheTtl;
    }

    public void setCrlCacheTtl(Duration crlCacheTtl) {
        this.crlCacheTtl = crlCacheTtl;
    }

    public int getMaxCrlBytes() {
        return maxCrlBytes;
    }

    public void setMaxCrlBytes(int maxCrlBytes) {
        this.maxCrlBytes = maxCrlBytes;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
