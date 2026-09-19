package com.winllc.certalert.revocation;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.domain.CachedCertificate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The CA certificates this application has been given, for the two jobs that need them.
 *
 * <p>OCSP names a certificate by hashes of its issuer's name and public key, which can only
 * be computed from the issuer's certificate - the leaf carries neither. And a CRL is signed
 * by the issuer, so telling the authority's list from whatever answered the URL needs the
 * same certificate. Neither is possible without them, which is why this is configuration
 * rather than something discovered: an issuer this application found for itself would be an
 * issuer somebody else could supply.
 *
 * <p>Loaded once, at startup, from a directory of PEM or DER files. An empty or unset
 * directory is an ordinary state and not an error - it means OCSP is unavailable and CRLs
 * go unverified, both of which the results then say.
 */
@Component
public class IssuerCertificates {

    private static final Logger log = LoggerFactory.getLogger(IssuerCertificates.class);

    /** By subject, which is what a leaf's issuer field names. */
    private final Map<String, List<X509Certificate>> bySubject = new HashMap<>();

    /** By subject key identifier, which is what a leaf's authority key identifier names. */
    private final Map<String, X509Certificate> byKeyId = new HashMap<>();

    private final int loaded;

    public IssuerCertificates(RevocationProperties properties) {
        List<X509Certificate> certificates = load(properties.getIssuerDirectory());
        for (X509Certificate certificate : certificates) {
            bySubject.computeIfAbsent(
                            certificate.getSubjectX500Principal().getName(), key -> new ArrayList<>())
                    .add(certificate);
            String keyId = RevocationEndpoints.subjectKeyId(certificate);
            if (keyId != null) {
                byKeyId.put(keyId.toLowerCase(Locale.ROOT), certificate);
            }
        }
        this.loaded = certificates.size();
        if (loaded > 0) {
            log.info("Loaded {} issuer certificate(s) for revocation checking", loaded);
        }
    }

    /** How many were loaded, so a status can say whether OCSP is possible at all. */
    public int size() {
        return loaded;
    }

    public boolean isEmpty() {
        return loaded == 0;
    }

    /**
     * The certificate of whoever issued this one.
     *
     * <p>By key identifier first. A CA that has been re-keyed issues under the same name
     * with a different key, and matching on the name alone would pick whichever of the two
     * was loaded first - which is the one case where getting it wrong matters, because the
     * signature check would then fail on a CRL that is perfectly good.
     */
    public Optional<X509Certificate> issuerOf(CachedCertificate certificate) {
        String keyId = certificate.getAuthorityKeyId();
        if (keyId != null) {
            X509Certificate byKey = byKeyId.get(keyId.toLowerCase(Locale.ROOT));
            if (byKey != null) {
                return Optional.of(byKey);
            }
        }
        List<X509Certificate> named = bySubject.get(certificate.getIssuerDn());
        // Several of the same name and nothing to tell them apart is not an answer.
        return named != null && named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /** Whoever signed this CRL, by the name it carries. */
    public Optional<X509Certificate> issuerNamed(String subjectDn) {
        List<X509Certificate> named = bySubject.get(subjectDn);
        return named != null && named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    private static List<X509Certificate> load(String directory) {
        if (directory == null || directory.isBlank()) {
            return List.of();
        }
        Path path = Path.of(directory.trim());
        if (!Files.isDirectory(path)) {
            log.warn("cert-alert.revocation.issuer-directory '{}' is not a directory; "
                    + "OCSP is unavailable and CRLs will not be verified", directory);
            return List.of();
        }
        List<X509Certificate> certificates = new ArrayList<>();
        try (Stream<Path> files = Files.list(path)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                certificates.addAll(read(file));
            }
        } catch (IOException e) {
            log.warn("Could not read issuer certificates from '{}'", directory, e);
        }
        return certificates;
    }

    /**
     * Whatever X.509 certificates a file holds. The factory reads PEM and DER alike, and a
     * PEM file holding a whole chain comes back as several - which is the usual way a CA
     * publishes one.
     */
    private static List<X509Certificate> read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            List<X509Certificate> found = CertificateFactory.getInstance("X.509")
                    .generateCertificates(in)
                    .stream()
                    .filter(X509Certificate.class::isInstance)
                    .map(X509Certificate.class::cast)
                    .toList();
            if (found.isEmpty()) {
                log.warn("No certificate in issuer file '{}'", file);
            }
            return found;
        } catch (IOException | CertificateException e) {
            // One unreadable file is not a reason to start without the rest of them.
            log.warn("Could not read issuer certificate '{}': {}", file, e.getMessage());
            return List.of();
        }
    }
}
