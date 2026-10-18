package com.winllc.certalert.security;

import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.revocation.IssuerCertificates;
import com.winllc.certalert.revocation.SignInRevocation;
import com.winllc.certalert.service.CertificateFingerprints;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * What a client certificate has to pass before it is matched to anybody, whichever way it
 * arrived: the issuer allowlist ({@code ENS_X509_ALLOWED_ISSUERS}) and revocation
 * ({@code ENS_X509_REVOCATION}).
 *
 * <p>Checked on every request that presents a certificate - a certificate is not remembered
 * by the session - with revocation answers cached (see {@link SignInRevocation}).
 *
 * <p>A revocation check needs the certificate of whoever issued it. It is looked for, in
 * order, in the chain the client presented (or the proxy forwarded, already validated), in
 * {@code ENS_X509_TRUSTED_CA_FILE}, in {@code cert-alert.revocation.issuer-directory}, and in
 * the TLS trust store - and is used only if its key verifies the certificate's signature.
 */
@Component
public class ClientCertificateChecks {

    private static final Logger log = LoggerFactory.getLogger(ClientCertificateChecks.class);

    private final EnsX509Properties settings;
    private final SignInRevocation revocation;
    private final TrustedCas trustedCas;
    private final IssuerCertificates issuers;
    private final Environment environment;
    private final ResourceLoader resources;
    private volatile List<X509Certificate> trustStore;

    public ClientCertificateChecks(
            EnsX509Properties settings,
            SignInRevocation revocation,
            TrustedCas trustedCas,
            IssuerCertificates issuers,
            Environment environment,
            ResourceLoader resources) {
        this.settings = settings;
        this.revocation = revocation;
        this.trustedCas = trustedCas;
        this.issuers = issuers;
        this.environment = environment;
        this.resources = resources;
    }

    /**
     * @param chain what was presented, the certificate first
     * @throws RefusedCertificateException where it may not sign anybody in
     */
    public void check(X509Certificate certificate, List<X509Certificate> chain) {
        String subject = certificate.getSubjectX500Principal().getName();
        List<String> allowed = settings.allowedIssuerList();
        if (!allowed.isEmpty() && !issuerAllowed(certificate.getIssuerX500Principal(), allowed)) {
            log.warn("Refusing client certificate '{}': its issuer '{}' is not in ENS_X509_ALLOWED_ISSUERS",
                    subject, certificate.getIssuerX500Principal().getName());
            throw new RefusedCertificateException(
                    RefusedCertificateException.Reason.ISSUER_NOT_ALLOWED, CertificateFingerprints.sha256(certificate));
        }
        if (!revocation.isEnabled()) {
            return;
        }
        SignInRevocation.Answer answer = revocation.check(certificate, issuerOf(certificate, chain));
        if (answer.status() == RevocationStatus.REVOKED) {
            log.warn("Refusing client certificate '{}': revoked ({})", subject, answer.detail());
            throw new RefusedCertificateException(
                    RefusedCertificateException.Reason.REVOKED, CertificateFingerprints.sha256(certificate));
        }
        if (answer.status() == RevocationStatus.UNKNOWN) {
            if (settings.isFailOpen()) {
                log.warn("Allowing client certificate '{}' although no OCSP responder or CRL answered for it "
                        + "(soft-fail; ENS_X509_REVOCATION_FAIL=closed refuses it instead): {}",
                        subject, answer.detail());
                return;
            }
            log.warn("Refusing client certificate '{}': its revocation status is unknown: {}", subject, answer.detail());
            throw new RefusedCertificateException(
                    RefusedCertificateException.Reason.REVOCATION_UNKNOWN, CertificateFingerprints.sha256(certificate));
        }
    }

    /** Compared as names, not as strings: spacing, case and attribute spelling do not matter. */
    static boolean issuerAllowed(X500Principal issuer, List<String> allowed) {
        for (String dn : allowed) {
            try {
                if (new X500Principal(dn).equals(issuer)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                log.warn("ENS_X509_ALLOWED_ISSUERS entry '{}' is not a DN (RFC 4514, e.g. CN=DoD ID CA-70,OU=PKI,"
                        + "OU=DoD,O=U.S. Government,C=US); it allows nothing", dn);
            }
        }
        return false;
    }

    /** Whoever issued it, from wherever there is a certificate whose key verifies it. */
    X509Certificate issuerOf(X509Certificate certificate, List<X509Certificate> chain) {
        List<X509Certificate> candidates = new ArrayList<>();
        if (chain.size() > 1) {
            candidates.addAll(chain.subList(1, chain.size()));
        }
        try {
            candidates.addAll(trustedCas.certificates());
        } catch (IllegalStateException e) {
            log.warn(e.getMessage());
        }
        candidates.addAll(issuers.all());
        candidates.addAll(trustStore());
        for (X509Certificate candidate : candidates) {
            if (!candidate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
                continue;
            }
            try {
                certificate.verify(candidate.getPublicKey());
                return candidate;
            } catch (java.security.GeneralSecurityException e) {
                // the same name, a different key: a re-keyed CA, keep looking
            }
        }
        return null;
    }

    /** The CAs in server.ssl.trust-store, read once, for a deployment that terminates TLS itself. */
    private List<X509Certificate> trustStore() {
        List<X509Certificate> held = trustStore;
        if (held != null) {
            return held;
        }
        List<X509Certificate> loaded = new ArrayList<>();
        String location = environment.getProperty("server.ssl.trust-store");
        if (location != null && !location.isBlank()
                && environment.getProperty("server.ssl.enabled", Boolean.class, false)) {
            try {
                String type = environment.getProperty("server.ssl.trust-store-type", "PKCS12");
                String password = environment.getProperty("server.ssl.trust-store-password", "");
                KeyStore store = KeyStore.getInstance(type);
                Resource resource = resources.getResource(location);
                try (InputStream in = resource.getInputStream()) {
                    store.load(in, password.toCharArray());
                }
                for (String alias : Collections.list(store.aliases())) {
                    Certificate certificate = store.getCertificate(alias);
                    if (certificate instanceof X509Certificate x509) {
                        loaded.add(x509);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read the TLS trust store {} for issuer certificates: {}", location, e.getMessage());
            }
        }
        trustStore = List.copyOf(loaded);
        return trustStore;
    }
}
