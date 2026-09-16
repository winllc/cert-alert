package com.winllc.certalert.security;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.service.CertificateFingerprints;
import java.security.cert.X509Certificate;
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
 * <p>Falling back to subject and subject-alternative names is available for directories
 * whose people hold certificates they do not publish, but it is off by default because it
 * trusts a name rather than a key.
 */
@Service
public class X509DirectoryUserDetailsService
        implements AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(X509DirectoryUserDetailsService.class);

    private final CachedCertificateRepository certificateRepository;
    private final DirectoryPrincipalResolver resolver;
    private final SecurityProperties properties;

    public X509DirectoryUserDetailsService(
            CachedCertificateRepository certificateRepository,
            DirectoryPrincipalResolver resolver,
            SecurityProperties properties) {
        this.certificateRepository = certificateRepository;
        this.resolver = resolver;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserDetails(PreAuthenticatedAuthenticationToken token) {
        X509Certificate certificate = certificateOf(token);
        String subject = certificate.getSubjectX500Principal().getName();

        Optional<DirectoryUser> byFingerprint = byPublishedCertificate(certificate);
        if (byFingerprint.isPresent()) {
            log.debug("Client certificate '{}' matched a certificate published by '{}'",
                    subject, byFingerprint.get().getDn());
            return resolver.principalFor(byFingerprint.get(), DirectoryPrincipal.AuthenticationMethod.X509);
        }

        if (properties.getX509().isRequireKnownCertificate()) {
            log.warn("Rejecting client certificate '{}': the directory publishes no such certificate", subject);
            throw new UsernameNotFoundException("Certificate is not published by the directory");
        }

        Set<String> identifiers = X509Identifiers.of(certificate);
        return resolver
                .findByAnyIdentifier(identifiers)
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

    /** The certificate whose owner is an IC Person, if the directory publishes it. */
    private Optional<DirectoryUser> byPublishedCertificate(X509Certificate certificate) {
        String fingerprint = CertificateFingerprints.sha256(certificate);
        List<CachedCertificate> matches = certificateRepository.findByFingerprint(fingerprint);
        return matches.stream()
                .map(CachedCertificate::getUser)
                .filter(java.util.Objects::nonNull)
                .findFirst();
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
