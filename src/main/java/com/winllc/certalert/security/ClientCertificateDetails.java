package com.winllc.certalert.security;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.List;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedGrantedAuthoritiesWebAuthenticationDetails;

/**
 * The usual request details of a certificate sign-in, and the whole chain that came with the
 * certificate - which the X.509 filter itself keeps only the first of, and which the
 * revocation check needs for the issuer.
 */
public class ClientCertificateDetails extends PreAuthenticatedGrantedAuthoritiesWebAuthenticationDetails {

    private final transient List<X509Certificate> chain;

    public ClientCertificateDetails(HttpServletRequest request) {
        super(request, List.of());
        Object attribute = request.getAttribute(ClientCertificateHeaderFilter.CERTIFICATE_ATTRIBUTE);
        this.chain = attribute instanceof X509Certificate[] certificates ? List.of(certificates) : List.of();
    }

    /** The certificate first, then whatever was presented or validated with it. */
    public List<X509Certificate> chain() {
        // Not kept when a session is stored: read back, a session's copy has no chain.
        return chain == null ? List.of() : chain;
    }
}
