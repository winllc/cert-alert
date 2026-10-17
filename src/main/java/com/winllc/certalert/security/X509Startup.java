package com.winllc.certalert.security;

import jakarta.annotation.PostConstruct;
import java.security.cert.X509Certificate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with an X.509 configuration that would be unsafe, rather than finding out
 * at the first sign-in - and says once, at startup, what the STIG would object to.
 *
 * <ul>
 *   <li>A setting that is not one of its values ({@code ENS_X509_REVOCATION},
 *       {@code ENS_X509_REVOCATION_FAIL}, {@code ENS_X509_SOURCE}) stops the start.
 *   <li>Header mode stops the start without a proxy secret of at least
 *       {@value ForwardedCertificates#MIN_SECRET_LENGTH} characters, without
 *       {@code ENS_X509_TRUSTED_CA_FILE} to validate the forwarded chain against, or with
 *       {@code SERVER_FORWARD_HEADERS_STRATEGY=native}, which rewrites the connection's
 *       address in place and so would let a client choose the address the proxy check sees.
 *   <li>Certificate sign-in with revocation checking off is allowed - it is the default, for
 *       upgrades - and warned about, since the STIG requires it.
 * </ul>
 */
@Component
public class X509Startup {

    private static final Logger log = LoggerFactory.getLogger(X509Startup.class);

    private final EnsX509Properties x509;
    private final SecurityProperties security;
    private final ForwardedCertificates forwarded;
    private final TrustedCas trustedCas;
    private final Environment environment;

    public X509Startup(
            EnsX509Properties x509,
            SecurityProperties security,
            ForwardedCertificates forwarded,
            TrustedCas trustedCas,
            Environment environment) {
        this.x509 = x509;
        this.security = security;
        this.forwarded = forwarded;
        this.trustedCas = trustedCas;
        this.environment = environment;
    }

    @PostConstruct
    void check() {
        EnsX509Properties.Revocation revocation = x509.revocationMode();
        boolean failOpen = x509.isFailOpen();
        boolean header = x509.isHeaderSource();

        if (header) {
            String secret = forwarded.proxySecret();
            if (secret == null || secret.length() < ForwardedCertificates.MIN_SECRET_LENGTH) {
                throw new IllegalStateException("ENS_X509_SOURCE=header needs ENS_X509_PROXY_SECRET_FILE or "
                        + "ENS_X509_PROXY_SECRET, at least " + ForwardedCertificates.MIN_SECRET_LENGTH
                        + " characters, which the proxy sends in " + ForwardedCertificates.PROXY_SECRET_HEADER
                        + " (e.g. openssl rand -hex 32)");
            }
            if (!trustedCas.isConfigured()) {
                throw new IllegalStateException("ENS_X509_SOURCE=header needs ENS_X509_TRUSTED_CA_FILE: the CA "
                        + "certificates (PEM) a forwarded certificate has to chain to");
            }
            List<X509Certificate> cas = trustedCas.certificates();
            if ("native".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "").trim())) {
                throw new IllegalStateException("ENS_X509_SOURCE=header cannot be combined with "
                        + "SERVER_FORWARD_HEADERS_STRATEGY=native, which rewrites the connection's address; "
                        + "use framework");
            }
            log.info("X.509 certificates are read from {} when sent by {} with {}, and must chain to one of {} "
                            + "CA certificate(s) in {}",
                    x509.getHeader(), x509.trustedProxyList(), ForwardedCertificates.PROXY_SECRET_HEADER,
                    cas.size(), x509.getTrustedCaFile());
        }

        boolean certificatesArrive = security.getX509().isEnabled()
                && (header || environment.getProperty("server.ssl.enabled", Boolean.class, false));
        if (certificatesArrive && revocation == EnsX509Properties.Revocation.OFF) {
            log.warn("X.509 sign-in is on and ENS_X509_REVOCATION is off: a revoked certificate the directory still "
                    + "publishes can sign in until the nightly revocation check finds it. The DISA STIG requires "
                    + "revocation checking at sign-in; set ENS_X509_REVOCATION to ocsp, crl or ocsp-then-crl");
        }
        if (certificatesArrive && revocation != EnsX509Properties.Revocation.OFF && failOpen) {
            log.warn("ENS_X509_REVOCATION_FAIL=open: a certificate whose revocation status cannot be found signs in "
                    + "anyway. The STIG expects closed");
        }
        if (revocation != EnsX509Properties.Revocation.OFF) {
            log.info("Client certificates are checked for revocation at sign-in: {}{}{}",
                    x509.getRevocation(),
                    x509.getCrlDir() == null ? "" : ", CRLs from " + x509.getCrlDir(),
                    x509.getOcspResponderUrl() == null ? "" : ", OCSP at " + x509.getOcspResponderUrl());
        }
    }
}
