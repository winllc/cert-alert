package com.winllc.certalert.security;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Client-certificate sign-in as the ENS platform baseline describes it, bound from
 * {@code ens.x509} and set from the {@code ENS_X509_*} environment variables in
 * application.yml - the same names and meanings as in every ENS product that signs in with
 * certificates.
 *
 * <p>These add to {@code cert-alert.security.x509}, which still decides whether certificates
 * sign anybody in and whether only a published certificate does.
 */
@ConfigurationProperties(prefix = "ens.x509")
public class EnsX509Properties {

    /** How the certificate's revocation is checked at sign-in. */
    public enum Revocation {
        OFF, OCSP, CRL, OCSP_THEN_CRL;

        /** What an unset {@code ENS_X509_REVOCATION} means: the STIG asks for a live check. */
        public static final Revocation DEFAULT = OCSP_THEN_CRL;

        static Revocation parse(String value) {
            if (value == null || value.isBlank()) {
                return DEFAULT;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "ENS_X509_REVOCATION must be off, ocsp, crl or ocsp-then-crl, not '" + value + "'");
            }
        }

        public boolean usesOcsp() {
            return this == OCSP || this == OCSP_THEN_CRL;
        }

        public boolean usesCrl() {
            return this == CRL || this == OCSP_THEN_CRL;
        }
    }

    /** Where the certificate comes from. */
    public enum Source {
        /** The TLS handshake this application terminated. */
        SERVLET,
        /** A header set by a TLS-terminating proxy in front. */
        HEADER
    }

    /**
     * {@code ENS_X509_REVOCATION}: off, ocsp, crl or ocsp-then-crl. Unset is ocsp-then-crl,
     * soft-fail (see {@link #isFailOpen}).
     */
    private String revocation;

    /** {@code ENS_X509_OCSP_RESPONDER_URL}: ask here rather than where the certificate says. */
    private String ocspResponderUrl;

    /**
     * {@code ENS_X509_CRL_DIR}: CRL files (DER or PEM), re-read when they change. With it set
     * no distribution point is fetched over the network - the air-gapped option.
     */
    private String crlDir;

    /**
     * {@code ENS_X509_REVOCATION_FAIL}: closed refuses when the status cannot be found; open
     * allows, with a warning. Unset follows {@code ENS_X509_REVOCATION}: open where that is
     * unset too, closed where it was chosen.
     */
    private String revocationFail;

    /** {@code ENS_X509_REVOCATION_CACHE}: how long a good or revoked answer is reused, per serial and issuer. */
    private Duration revocationCache = Duration.ofMinutes(10);

    /**
     * {@code ENS_X509_ALLOWED_ISSUERS}: when set, only certificates issued by these DNs sign
     * in, whatever else the trust store would accept. Separated by semicolons or new lines,
     * since a DN has commas of its own.
     */
    private String allowedIssuers;

    /**
     * {@code ENS_X509_USER_FILTER}: an LDAP filter that finds the person a certificate the
     * directory does not publish belongs to, with {@code {upn}}, {@code {edipi}},
     * {@code {email}} and {@code {uuid}} filled in from it - only consulted when
     * {@code cert-alert.security.x509.require-known-certificate} is false.
     */
    private String userFilter;

    /** {@code ENS_X509_SOURCE}: servlet or header. */
    private String source = "servlet";

    /** {@code ENS_X509_HEADER}: where a proxy puts the certificate, URL-encoded PEM. */
    private String header = "X-SSL-Client-Cert";

    /** {@code ENS_X509_TRUSTED_PROXIES}: the addresses (CIDRs) a forwarded certificate is accepted from. */
    private String trustedProxies = "127.0.0.1/32,::1/128";

    /** {@code ENS_X509_PROXY_SECRET}: what the proxy sends in {@code X-ENS-Proxy-Secret}. */
    private String proxySecret;

    /** {@code ENS_X509_PROXY_SECRET_FILE}: the same, from a file; wins over the value. */
    private String proxySecretFile;

    /**
     * {@code ENS_X509_TRUSTED_CA_FILE}: PEM certificates of the CAs (and intermediates) a
     * forwarded certificate must chain to. Required in header mode; also searched for an
     * issuer certificate when checking revocation.
     */
    private String trustedCaFile;

    public String getRevocation() {
        return revocation;
    }

    public void setRevocation(String revocation) {
        this.revocation = revocation;
    }

    /** What {@link #getRevocation} says, or an exception naming the setting where it says nothing usable. */
    public Revocation revocationMode() {
        return Revocation.parse(revocation);
    }

    public String getOcspResponderUrl() {
        return ocspResponderUrl;
    }

    public void setOcspResponderUrl(String ocspResponderUrl) {
        this.ocspResponderUrl = blankToNull(ocspResponderUrl);
    }

    public String getCrlDir() {
        return crlDir;
    }

    public void setCrlDir(String crlDir) {
        this.crlDir = blankToNull(crlDir);
    }

    public String getRevocationFail() {
        return revocationFail;
    }

    public void setRevocationFail(String revocationFail) {
        this.revocationFail = revocationFail;
    }

    /** Whether {@code ENS_X509_REVOCATION} was left unset, and so means the default check. */
    public boolean isRevocationDefaulted() {
        return revocation == null || revocation.isBlank();
    }

    /**
     * Whether an unknown status is let through: {@code open}, rather than {@code closed}.
     *
     * <p>Unset, it is open when {@code ENS_X509_REVOCATION} is unset as well. That default
     * is a live check that refuses what it finds revoked and lets through, with a warning,
     * what it cannot get an answer about - so a site whose responders cannot be reached,
     * an air-gapped one above all, still signs people in. A site that chose a mode itself
     * chose closed by leaving this unset in earlier releases, and still gets it.
     */
    public boolean isFailOpen() {
        String value = revocationFail == null ? "" : revocationFail.trim().toLowerCase(Locale.ROOT);
        if (!value.isEmpty() && !value.equals("closed") && !value.equals("open")) {
            throw new IllegalArgumentException(
                    "ENS_X509_REVOCATION_FAIL must be closed or open, not '" + revocationFail + "'");
        }
        return value.isEmpty() ? isRevocationDefaulted() : value.equals("open");
    }

    public Duration getRevocationCache() {
        return revocationCache;
    }

    public void setRevocationCache(Duration revocationCache) {
        this.revocationCache = revocationCache == null ? Duration.ofMinutes(10) : revocationCache;
    }

    /** The allowlist, one DN each; empty allows any issuer the trust store does. */
    public List<String> allowedIssuerList() {
        if (allowedIssuers == null || allowedIssuers.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedIssuers.split("[;\\n]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getAllowedIssuers() {
        return allowedIssuers;
    }

    public void setAllowedIssuers(String allowedIssuers) {
        this.allowedIssuers = allowedIssuers;
    }

    public String getUserFilter() {
        return userFilter;
    }

    public void setUserFilter(String userFilter) {
        this.userFilter = blankToNull(userFilter);
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    /** What {@link #getSource} says, or an exception naming the setting. */
    public Source sourceMode() {
        String value = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", "servlet" -> Source.SERVLET;
            case "header" -> Source.HEADER;
            default -> throw new IllegalArgumentException(
                    "ENS_X509_SOURCE must be servlet or header, not '" + source + "'");
        };
    }

    public boolean isHeaderSource() {
        return sourceMode() == Source.HEADER;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header == null || header.isBlank() ? "X-SSL-Client-Cert" : header.trim();
    }

    /** The trusted proxies, one CIDR or address each. */
    public List<String> trustedProxyList() {
        if (trustedProxies == null || trustedProxies.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trustedProxies.split("[;,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(String trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public String getProxySecret() {
        return proxySecret;
    }

    public void setProxySecret(String proxySecret) {
        this.proxySecret = blankToNull(proxySecret);
    }

    public String getProxySecretFile() {
        return proxySecretFile;
    }

    public void setProxySecretFile(String proxySecretFile) {
        this.proxySecretFile = blankToNull(proxySecretFile);
    }

    public String getTrustedCaFile() {
        return trustedCaFile;
    }

    public void setTrustedCaFile(String trustedCaFile) {
        this.trustedCaFile = blankToNull(trustedCaFile);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
