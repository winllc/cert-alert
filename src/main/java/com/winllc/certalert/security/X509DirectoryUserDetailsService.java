package com.winllc.certalert.security;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.service.CertificateFingerprints;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a client certificate to the person who holds it.
 *
 * <p>The certificate is matched by the SHA-256 fingerprint of its DER against the
 * certificates already cached from the directory. That is a far stronger binding than the
 * usual approach of pattern-matching a subject name: it authenticates the exact bytes the
 * directory publishes for that person, so a certificate issued to a different subject with
 * a colliding common name does not get in, and revoking access is a matter of removing the
 * certificate from the directory.
 *
 * <p>Only certificates belonging to an IC Person authenticate. A certificate published by
 * an IC Non-Person Entity identifies a server, not somebody who should hold a session.
 *
 * <p>Being published is not the same as being good. A directory keeps what it was given, so
 * it goes on publishing a certificate after its authority has revoked it and after it has
 * expired - and the TLS handshake in front of this has not necessarily asked about either.
 * The cache has: a certificate it knows to be revoked, or whose dates say it is not good
 * now, is refused here whatever the directory still publishes.
 *
 * <p>Before any of that, every certificate - published or not - is held to the ENS
 * baseline's checks ({@link ClientCertificateChecks}): an issuer on
 * {@code ENS_X509_ALLOWED_ISSUERS} where that is set, and not revoked where
 * {@code ENS_X509_REVOCATION} asks.
 *
 * <p>Falling back to subject and subject-alternative names is available for directories
 * whose people hold certificates they do not publish, but it is off by default because it
 * trusts a name rather than a key. Even then only a uid or an address is matched, against
 * what the directory publishes for a person - never a common name, and never an address
 * added in this application. Where {@code ENS_X509_USER_FILTER} is set, that filter - with
 * the certificate's UPN, EDIPI, address or FASC-N UUID filled in - is what finds them
 * instead ({@link CertificateMapping}), and it has to find exactly one.
 */
@Service
public class X509DirectoryUserDetailsService
        implements AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(X509DirectoryUserDetailsService.class);

    private final CachedCertificateRepository certificateRepository;
    private final DirectoryPrincipalResolver resolver;
    private final SecurityProperties properties;
    private final ClientCertificateChecks checks;
    private final CertificateMapping mapping;
    private final Clock clock;

    public X509DirectoryUserDetailsService(
            CachedCertificateRepository certificateRepository,
            DirectoryPrincipalResolver resolver,
            SecurityProperties properties,
            ClientCertificateChecks checks,
            CertificateMapping mapping,
            Clock clock) {
        this.certificateRepository = certificateRepository;
        this.resolver = resolver;
        this.properties = properties;
        this.checks = checks;
        this.mapping = mapping;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserDetails(PreAuthenticatedAuthenticationToken token) {
        X509Certificate certificate = certificateOf(token);
        String subject = certificate.getSubjectX500Principal().getName();
        checks.check(certificate, chainOf(token, certificate));

        Optional<DirectoryUser> byFingerprint = byPublishedCertificate(certificate, subject);
        if (byFingerprint.isPresent()) {
            log.debug("Client certificate '{}' matched a certificate published by '{}'",
                    subject, byFingerprint.get().getDn());
            return resolver.principalFor(byFingerprint.get(), DirectoryPrincipal.AuthenticationMethod.X509);
        }

        if (properties.getX509().isRequireKnownCertificate()) {
            log.warn("Rejecting client certificate '{}': the directory publishes no such certificate", subject);
            throw new UsernameNotFoundException("Certificate is not published by the directory");
        }

        if (mapping.isConfigured()) {
            return mapping.find(certificate)
                    .map(user -> {
                        log.debug("Client certificate '{}' mapped to directory entry '{}' by ENS_X509_USER_FILTER",
                                subject, user.getDn());
                        return (UserDetails) resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.X509);
                    })
                    .orElseThrow(() -> new UsernameNotFoundException("ENS_X509_USER_FILTER found nobody for this certificate"));
        }

        Set<String> identifiers = X509Identifiers.of(certificate);
        return resolver
                .findByDirectoryIdentifier(identifiers)
                .map(user -> {
                    log.debug("Client certificate '{}' matched directory entry '{}' by name", subject, user.getDn());
                    return (UserDetails)
                            resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.X509);
                })
                .orElseThrow(() -> {
                    log.warn("Rejecting client certificate '{}': no directory entry matches {}", subject, identifiers);
                    return new UsernameNotFoundException("No directory entry matches this certificate");
                });
    }

    /**
     * The IC Person who publishes this certificate, if the directory has one who does and
     * the certificate is still good.
     *
     * @throws RefusedCertificateException when a person publishes it, but it has been revoked
     *     or its dates say it is not good now
     */
    private Optional<DirectoryUser> byPublishedCertificate(X509Certificate certificate, String subject) {
        String fingerprint = CertificateFingerprints.sha256(certificate);
        List<CachedCertificate> matches = certificateRepository.findByFingerprint(fingerprint);
        List<CachedCertificate> owned = matches.stream()
                .filter(match -> match.getUser() != null)
                .toList();
        if (owned.isEmpty()) {
            return Optional.empty();
        }

        // Revocation belongs to the certificate, not to the entry publishing it: any copy
        // the authority has been asked about and said revoked is the answer for all of them.
        Optional<CachedCertificate> revoked = matches.stream().filter(CachedCertificate::isRevoked).findFirst();
        if (revoked.isPresent()) {
            log.warn("Refusing client certificate '{}' published by '{}': its authority revoked it{}{}",
                    subject, owned.getFirst().getUser().getDn(),
                    revoked.get().getRevokedAt() == null ? "" : " on " + revoked.get().getRevokedAt(),
                    revoked.get().getRevocationReason() == null
                            ? ""
                            : " (" + revoked.get().getRevocationReason() + ")");
            throw new RefusedCertificateException(RefusedCertificateException.Reason.REVOKED, fingerprint);
        }
        CachedCertificate cached = owned.getFirst();
        Instant now = Instant.now(clock);
        if (cached.getNotAfter() != null && now.isAfter(cached.getNotAfter())) {
            log.warn("Refusing client certificate '{}' published by '{}': it expired on {}",
                    subject, cached.getUser().getDn(), cached.getNotAfter());
            throw new RefusedCertificateException(RefusedCertificateException.Reason.EXPIRED, fingerprint);
        }
        if (cached.getNotBefore() != null && now.isBefore(cached.getNotBefore())) {
            log.warn("Refusing client certificate '{}' published by '{}': it is not valid until {}",
                    subject, cached.getUser().getDn(), cached.getNotBefore());
            throw new RefusedCertificateException(RefusedCertificateException.Reason.NOT_YET_VALID, fingerprint);
        }
        return Optional.of(cached.getUser());
    }

    /** The chain that came with it, where the filter recorded one; the certificate alone otherwise. */
    private static List<X509Certificate> chainOf(PreAuthenticatedAuthenticationToken token, X509Certificate certificate) {
        if (token.getDetails() instanceof ClientCertificateDetails details
                && !details.chain().isEmpty()
                && details.chain().getFirst().equals(certificate)) {
            return details.chain();
        }
        return List.of(certificate);
    }

    /**
     * The X.509 filter puts the certificate in the token's credentials. Anything else means
     * the filter chain has been rewired, and guessing would be worse than refusing.
     */
    private X509Certificate certificateOf(PreAuthenticatedAuthenticationToken token) {
        if (token.getCredentials() instanceof X509Certificate certificate) {
            return certificate;
        }
        throw new UsernameNotFoundException("Pre-authenticated token carried no client certificate");
    }
}
