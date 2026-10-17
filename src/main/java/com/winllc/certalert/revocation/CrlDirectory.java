package com.winllc.certalert.revocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CRL;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CRLs from a directory of files ({@code ENS_X509_CRL_DIR}): the air-gapped way to check
 * revocation, where somebody copies the authorities' lists in and nothing is fetched.
 *
 * <p>DER or PEM, any number per file, any file names. Read again when anything in the
 * directory changes - a file added, removed, replaced or touched - looked at no more than
 * every few seconds. Of the lists an issuer has signed, the most recent is the one used.
 */
final class CrlDirectory {

    private static final Logger log = LoggerFactory.getLogger(CrlDirectory.class);

    /** How often the directory is looked at for changes, at most. */
    private static final Duration LOOK_EVERY = Duration.ofSeconds(5);

    private final Path directory;
    private final Clock clock;
    private volatile Snapshot snapshot = new Snapshot(List.of(), List.of(), Instant.EPOCH);

    private record Stamp(String name, long modified, long size) {}

    private record Snapshot(List<Stamp> stamps, List<X509CRL> crls, Instant lookedAt) {}

    CrlDirectory(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    Path directory() {
        return directory;
    }

    /** The most recent list this issuer has signed, if the directory holds one. */
    Optional<X509CRL> latestFrom(X509Certificate issuer) {
        X509CRL latest = null;
        for (X509CRL crl : current()) {
            if (!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                continue;
            }
            try {
                crl.verify(issuer.getPublicKey());
            } catch (GeneralSecurityException e) {
                log.warn("A CRL in {} names {} as its issuer but is not signed by it: {}",
                        directory, issuer.getSubjectX500Principal().getName(), e.getMessage());
                continue;
            }
            if (latest == null || crl.getThisUpdate().after(latest.getThisUpdate())) {
                latest = crl;
            }
        }
        return Optional.ofNullable(latest);
    }

    private synchronized List<X509CRL> current() {
        Snapshot held = snapshot;
        Instant now = Instant.now(clock);
        if (now.isBefore(held.lookedAt().plus(LOOK_EVERY))) {
            return held.crls();
        }
        List<Stamp> stamps = stamps();
        if (stamps.equals(held.stamps())) {
            snapshot = new Snapshot(held.stamps(), held.crls(), now);
            return held.crls();
        }
        List<X509CRL> crls = new ArrayList<>();
        for (Stamp stamp : stamps) {
            crls.addAll(read(directory.resolve(stamp.name())));
        }
        log.info("Read {} CRL(s) from {}", crls.size(), directory);
        snapshot = new Snapshot(stamps, List.copyOf(crls), now);
        return snapshot.crls();
    }

    private List<Stamp> stamps() {
        if (!Files.isDirectory(directory)) {
            log.warn("ENS_X509_CRL_DIR {} is not a directory; no CRL can be found there", directory);
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Stamp> stamps = new ArrayList<>();
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                stamps.add(new Stamp(file.getFileName().toString(),
                        Files.getLastModifiedTime(file).toMillis(), Files.size(file)));
            }
            return stamps;
        } catch (IOException e) {
            log.warn("Could not list ENS_X509_CRL_DIR {}: {}", directory, e.getMessage());
            return List.of();
        }
    }

    private static List<X509CRL> read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            List<X509CRL> crls = new ArrayList<>();
            for (CRL crl : CertificateFactory.getInstance("X.509").generateCRLs(in)) {
                if (crl instanceof X509CRL x509) {
                    crls.add(x509);
                }
            }
            return crls;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // One file that is not a CRL is not a reason to ignore the rest.
            log.warn("Could not read a CRL from {}: {}", file, e.getMessage());
            return List.of();
        }
    }
}
