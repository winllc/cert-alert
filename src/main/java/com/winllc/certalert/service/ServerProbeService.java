package com.winllc.certalert.service;

import com.winllc.certalert.config.ProbeProperties;
import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.ProbeFinding;
import com.winllc.certalert.repository.DirectoryServerRepository;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks a server what certificate it is actually serving, and compares the answer with what
 * the directory publishes for it.
 *
 * <p>Those are two different facts, and the gap between them is the one this application
 * could not otherwise see. Everything else here reads the directory: the directory records
 * what was <em>issued</em>. Whether the new certificate was ever installed is known only to
 * the endpoint, and a renewal that was recorded and never deployed reads as perfectly
 * healthy on every page until the day the old one expires.
 *
 * <p>Nothing is stored. It is a question asked at a moment about a thing that changes when
 * somebody restarts a service, and an answer from last Tuesday would be worse than no
 * answer. The audit trail records that it was asked, and by whom.
 */
@Service
public class ServerProbeService {

    private static final Logger log = LoggerFactory.getLogger(ServerProbeService.class);

    private final DirectoryServerRepository servers;
    private final TlsEndpointProbe probe;
    private final AuditService audit;
    private final ProbeProperties properties;
    private final Clock clock;

    public ServerProbeService(
            DirectoryServerRepository servers,
            TlsEndpointProbe probe,
            AuditService audit,
            ProbeProperties properties,
            Clock clock) {
        this.servers = servers;
        this.probe = probe;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** What the endpoint presented, and what is wrong with it. */
    public record Result(
            String host,
            int port,
            boolean reachable,
            String error,
            String protocol,
            String cipherSuite,
            Long elapsedMillis,
            Presented presented,
            List<String> chain,
            Expected expected,
            Set<ProbeFinding> findings,
            Instant checkedAt) {

        /** Whether anything found needs doing something about. */
        public boolean hasProblem() {
            return findings.stream().anyMatch(ProbeFinding::isProblem);
        }
    }

    /** The leaf certificate the endpoint sent. */
    public record Presented(
            String subjectDn,
            String issuerDn,
            String serialNumber,
            Instant notBefore,
            Instant notAfter,
            String sha256Fingerprint,
            String signatureAlgorithm,
            String keyAlgorithm,
            List<String> subjectAlternativeNames) {}

    /** The certificate the directory says it should be serving, where it publishes one. */
    public record Expected(String serialNumber, Instant notBefore, Instant notAfter, String sha256Fingerprint) {}

    /**
     * Probes one server.
     *
     * @param port the port to ask on, or null for the one its URL names
     * @param actor who asked, for the audit trail
     * @throws ProbeUnavailableException if probing is switched off, or the entry says
     *     nothing about where the server is
     */
    @Transactional
    public Result probe(Long serverId, Integer port, String actor) {
        if (!properties.isEnabled()) {
            throw new ProbeUnavailableException(
                    "Probing unavailable", "Endpoint probing is switched off for this deployment");
        }
        DirectoryServer server = servers.findWithCertificatesById(serverId)
                .orElseThrow(() -> ResourceNotFoundException.server(serverId));
        EndpointAddress address = EndpointAddress.of(server, port, properties.getDefaultPort())
                .orElseThrow(() -> new ProbeUnavailableException(
                        "Nowhere to probe",
                        "This entry publishes neither a serverURL nor an icServerAddress, so there is "
                                + "no address to connect to"));

        Instant now = Instant.now(clock);
        Optional<CachedCertificate> latest = CertificateIssuance.mostRecentlyIssued(server.getCertificates());
        Expected expected = latest.map(certificate -> new Expected(
                        certificate.getSerialNumber(),
                        certificate.getNotBefore(),
                        certificate.getNotAfter(),
                        certificate.getSha256Fingerprint()))
                .orElse(null);

        Result result;
        try {
            TlsEndpointProbe.Handshake handshake = probe.probe(address.host(), address.port());
            result = describe(address, handshake, server.getCertificates(), latest.orElse(null), expected, now);
        } catch (EndpointProbeException e) {
            log.info("Probe of {} for '{}' failed: {}", address, server.getDn(), e.getMessage());
            result = new Result(
                    address.host(), address.port(), false, e.getMessage(),
                    null, null, null, null, List.of(), expected, Set.of(), now);
        }

        audit.record(AuditEvent.about(AuditEvent.SubjectRef.of(server), AuditAction.ENDPOINT_PROBED, summary(result), now)
                .by(actor)
                .forCertificate(result.presented() == null ? null : result.presented().sha256Fingerprint()));
        return result;
    }

    /** The port the page offers before anybody changes it. */
    public int defaultPortFor(DirectoryServer server) {
        return EndpointAddress.defaultPortFor(server, properties.getDefaultPort());
    }

    // -------------------------------------------------------------------------------------
    // Reading the answer
    // -------------------------------------------------------------------------------------

    private Result describe(
            EndpointAddress address,
            TlsEndpointProbe.Handshake handshake,
            Collection<CachedCertificate> cached,
            CachedCertificate latest,
            Expected expected,
            Instant now) {

        X509Certificate leaf = handshake.leaf();
        String fingerprint = fingerprintOf(leaf);
        List<String> names = dnsNames(leaf);

        Set<ProbeFinding> findings = EnumSet.noneOf(ProbeFinding.class);
        findings.addAll(comparison(fingerprint, cached, latest));

        if (leaf.getNotAfter().toInstant().isBefore(now)) {
            findings.add(ProbeFinding.EXPIRED);
        }
        if (leaf.getNotBefore().toInstant().isAfter(now)) {
            findings.add(ProbeFinding.NOT_YET_VALID);
        }
        if (!hostMatches(address.host(), leaf, names)) {
            findings.add(ProbeFinding.NAME_MISMATCH);
        }
        boolean selfSigned = leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal());
        if (selfSigned) {
            findings.add(ProbeFinding.SELF_SIGNED);
        } else if (handshake.chain().size() == 1) {
            // A self-signed certificate has no intermediates to send and is not missing any.
            findings.add(ProbeFinding.NO_INTERMEDIATES);
        }

        return new Result(
                address.host(),
                address.port(),
                true,
                null,
                handshake.protocol(),
                handshake.cipherSuite(),
                handshake.elapsed().toMillis(),
                new Presented(
                        leaf.getSubjectX500Principal().getName(),
                        leaf.getIssuerX500Principal().getName(),
                        leaf.getSerialNumber().toString(16),
                        leaf.getNotBefore().toInstant(),
                        leaf.getNotAfter().toInstant(),
                        fingerprint,
                        leaf.getSigAlgName(),
                        leaf.getPublicKey().getAlgorithm(),
                        names),
                handshake.chain().stream()
                        .map(certificate -> certificate.getSubjectX500Principal().getName())
                        .toList(),
                expected,
                findings,
                now);
    }

    /**
     * The question the probe exists to answer: is this the certificate the directory says
     * it should be serving?
     */
    private static Set<ProbeFinding> comparison(
            String fingerprint, Collection<CachedCertificate> cached, CachedCertificate latest) {

        if (latest == null) {
            return EnumSet.of(ProbeFinding.NOTHING_PUBLISHED);
        }
        if (fingerprint.equalsIgnoreCase(latest.getSha256Fingerprint())) {
            return EnumSet.of(ProbeFinding.SERVING_CURRENT);
        }
        boolean published = cached.stream()
                .anyMatch(certificate -> fingerprint.equalsIgnoreCase(certificate.getSha256Fingerprint()));
        // Published but not the newest: the renewal is in the directory and not on the
        // server, which is the failure this whole feature is for.
        return EnumSet.of(published ? ProbeFinding.SERVING_SUPERSEDED : ProbeFinding.NOT_PUBLISHED);
    }

    /**
     * Whether the certificate is good for the name that was asked for.
     *
     * <p>Subject alternative names decide it, with the common name as the fallback for a
     * certificate old enough not to carry any. A wildcard covers one label and not a dot,
     * so {@code *.example.gov} is good for {@code web01.example.gov} and not for
     * {@code web01.eu.example.gov}. An address is compared literally: a certificate naming
     * an IP is unusual, and a name never matches one.
     */
    static boolean hostMatches(String host, X509Certificate leaf, List<String> names) {
        String wanted = host.toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>(names);
        if (candidates.isEmpty()) {
            commonNameOf(leaf).ifPresent(candidates::add);
        }
        for (String name : candidates) {
            if (matches(wanted, name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String host, String name) {
        if (name.equals(host)) {
            return true;
        }
        if (!name.startsWith("*.")) {
            return false;
        }
        String suffix = name.substring(1);
        if (!host.endsWith(suffix)) {
            return false;
        }
        // One label deep: the wildcard stands for a label, not for the rest of the name.
        return host.substring(0, host.length() - suffix.length()).indexOf('.') < 0;
    }

    private static Optional<String> commonNameOf(X509Certificate leaf) {
        for (String part : leaf.getSubjectX500Principal().getName().split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                return Optional.of(trimmed.substring(3));
            }
        }
        return Optional.empty();
    }

    private static List<String> dnsNames(X509Certificate leaf) {
        try {
            Collection<List<?>> names = leaf.getSubjectAlternativeNames();
            if (names == null) {
                return List.of();
            }
            List<String> dns = new ArrayList<>();
            for (List<?> entry : names) {
                // 2 is dNSName, 7 is iPAddress: both are names an endpoint answers to.
                if (entry.size() >= 2 && (Integer.valueOf(2).equals(entry.get(0)) || Integer.valueOf(7).equals(entry.get(0)))) {
                    dns.add(String.valueOf(entry.get(1)));
                }
            }
            return dns;
        } catch (CertificateParsingException e) {
            log.debug("Could not read the names from the presented certificate", e);
            return List.of();
        }
    }

    private static String fingerprintOf(X509Certificate leaf) {
        try {
            return CertificateFingerprints.sha256(leaf.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new EndpointProbeException("The presented certificate could not be re-encoded to compare it", e);
        }
    }

    /** One line for the audit trail: what was asked, and what came back. */
    private static String summary(Result result) {
        if (!result.reachable()) {
            return "Probed %s:%d - unreachable".formatted(result.host(), result.port());
        }
        String findings = result.findings().stream()
                .map(ProbeFinding::label)
                .reduce((one, two) -> one + "; " + two)
                .orElse("nothing to report");
        return "Probed %s:%d - %s".formatted(result.host(), result.port(), findings);
    }
}
