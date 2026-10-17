package com.winllc.certalert.security;

import com.winllc.certalert.service.CertificateFingerprints;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Where the certificate comes from, ahead of the X.509 sign-in filter.
 *
 * <p>In header mode ({@code ENS_X509_SOURCE=header}) the certificate a trusted proxy
 * forwarded, once {@link ForwardedCertificates} has believed it, is put where the sign-in
 * filter reads a TLS client certificate - and one that is not believed is not, and is
 * recorded as an {@code auth.login} failure with the reason. In that mode a certificate from
 * any TLS handshake of the application's own is ignored: the source is the one configured.
 *
 * <p>In servlet mode, the default, this does nothing: the header is never read.
 *
 * <p>Not a bean, for the same reason as {@link ConsentFilter}.
 */
public final class ClientCertificateHeaderFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientCertificateHeaderFilter.class);

    /** Where the servlet container, and so the X.509 filter, keeps the client's chain. */
    public static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final EnsX509Properties settings;
    private final ForwardedCertificates forwarded;
    private final AuthenticationEventPublisher events;

    public ClientCertificateHeaderFilter(
            EnsX509Properties settings, ForwardedCertificates forwarded, AuthenticationEventPublisher events) {
        this.settings = settings;
        this.forwarded = forwarded;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!settings.isHeaderSource()) {
            chain.doFilter(request, response);
            return;
        }
        request.removeAttribute(CERTIFICATE_ATTRIBUTE);
        try {
            List<X509Certificate> certificates = forwarded.extract(request);
            if (certificates != null) {
                request.setAttribute(CERTIFICATE_ATTRIBUTE, certificates.toArray(X509Certificate[]::new));
            }
        } catch (ForwardedCertificates.Refused refused) {
            log.warn("Ignoring a forwarded client certificate: {}", refused.getMessage());
            X509Certificate certificate = refused.certificate();
            String name = certificate == null ? null : certificate.getSubjectX500Principal().getName();
            events.publishAuthenticationFailure(
                    new RefusedCertificateException(
                            refused.reason(), certificate == null ? null : CertificateFingerprints.sha256(certificate)),
                    new PreAuthenticatedAuthenticationToken(name, certificate));
        }
        chain.doFilter(request, response);
    }
}
