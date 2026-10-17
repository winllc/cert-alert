package com.winllc.certalert.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The CA certificates in {@code ENS_X509_TRUSTED_CA_FILE}: what a certificate forwarded by a
 * proxy has to chain to, and somewhere to find a certificate's issuer for a revocation check.
 *
 * <p>PEM (or DER), any number. Re-read when the file changes, so a CA added for a new issuing
 * authority needs no restart.
 */
@Component
public class TrustedCas {

    private final EnsX509Properties settings;
    private volatile Loaded loaded;

    private record Loaded(String path, long modified, List<X509Certificate> certificates) {}

    public TrustedCas(EnsX509Properties settings) {
        this.settings = settings;
    }

    /** Whether a file is configured at all. */
    public boolean isConfigured() {
        return settings.getTrustedCaFile() != null;
    }

    /**
     * What the file holds now.
     *
     * @throws IllegalStateException where it is configured and cannot be read, or holds nothing
     */
    public List<X509Certificate> certificates() {
        String file = settings.getTrustedCaFile();
        if (file == null) {
            return List.of();
        }
        Path path = Path.of(file);
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            Loaded current = loaded;
            if (current == null || !current.path().equals(file) || current.modified() != modified) {
                List<X509Certificate> certificates;
                try (InputStream in = Files.newInputStream(path)) {
                    certificates = CertificateFactory.getInstance("X.509").generateCertificates(in).stream()
                            .filter(X509Certificate.class::isInstance)
                            .map(X509Certificate.class::cast)
                            .toList();
                }
                if (certificates.isEmpty()) {
                    throw new IllegalStateException("ENS_X509_TRUSTED_CA_FILE " + file + " holds no certificate");
                }
                current = new Loaded(file, modified, certificates);
                loaded = current;
            }
            return current.certificates();
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException(
                    "ENS_X509_TRUSTED_CA_FILE " + file + " could not be read: " + e.getMessage(), e);
        }
    }
}
