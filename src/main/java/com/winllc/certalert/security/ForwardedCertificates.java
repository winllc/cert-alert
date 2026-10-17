package com.winllc.certalert.security;

import com.winllc.certalert.net.AddressRange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A client certificate forwarded by a TLS-terminating proxy ({@code ENS_X509_SOURCE=header}).
 *
 * <p>A header is something anybody can send, so one is believed only when all of this holds:
 *
 * <ul>
 *   <li>the connection comes straight from an address in {@code ENS_X509_TRUSTED_PROXIES} -
 *       the connection's own address, never one a forwarded-for header claims;
 *   <li>it carries {@code X-ENS-Proxy-Secret}, matching {@code ENS_X509_PROXY_SECRET_FILE} or
 *       {@code ENS_X509_PROXY_SECRET} (compared in constant time) - an address alone does not
 *       prove the sender is the proxy, when other pods or processes can share it;
 *   <li>the certificate chains, by the platform's path builder, to a CA in
 *       {@code ENS_X509_TRUSTED_CA_FILE}, is in date, and is not restricted to some use other
 *       than client authentication - because the proxy checked it is not a reason for this
 *       application not to.
 * </ul>
 *
 * <p>The header is URL-encoded PEM, as nginx's {@code $ssl_client_escaped_cert} sends it;
 * plain PEM, and PEM with its line breaks turned to spaces, are read as well.
 */
@Component
public class ForwardedCertificates {

    private static final Logger log = LoggerFactory.getLogger(ForwardedCertificates.class);

    /** Where the proxy sends the shared secret. */
    public static final String PROXY_SECRET_HEADER = "X-ENS-Proxy-Secret";

    /** Shorter than this is guessable, and refused at startup. */
    public static final int MIN_SECRET_LENGTH = 32;

    private static final String CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
    private static final String ANY_USAGE = "2.5.29.37.0";

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN[ +]CERTIFICATE-----(.*?)-----END[ +]CERTIFICATE-----", Pattern.DOTALL);

    private final EnsX509Properties settings;
    private final TrustedCas trustedCas;

    public ForwardedCertificates(EnsX509Properties settings, TrustedCas trustedCas) {
        this.settings = settings;
        this.trustedCas = trustedCas;
    }

    /** Why a forwarded certificate was not believed, and the certificate where one could be read. */
    public static final class Refused extends Exception {

        private final RefusedCertificateException.Reason reason;
        private final transient X509Certificate certificate;

        Refused(RefusedCertificateException.Reason reason, String detail, X509Certificate certificate) {
            super(detail);
            this.reason = reason;
            this.certificate = certificate;
        }

        public RefusedCertificateException.Reason reason() {
            return reason;
        }

        public X509Certificate certificate() {
            return certificate;
        }
    }

    /**
     * The validated chain - the certificate, then up to and including the trust anchor - or
     * null where the request carries no certificate header.
     *
     * @throws Refused where it carries one that is not to be believed
     */
    public List<X509Certificate> extract(HttpServletRequest request) throws Refused {
        String header = request.getHeader(settings.getHeader());
        if (header == null || header.isBlank()) {
            return null;
        }
        String peer = peerAddress(request);
        if (!fromTrustedProxy(peer)) {
            throw new Refused(RefusedCertificateException.Reason.UNTRUSTED_PROXY,
                    settings.getHeader() + " from " + peer + ", which is not in ENS_X509_TRUSTED_PROXIES", null);
        }
        if (!carriesSecret(request)) {
            throw new Refused(RefusedCertificateException.Reason.UNTRUSTED_PROXY,
                    settings.getHeader() + " from " + peer + " without the right " + PROXY_SECRET_HEADER, null);
        }
        List<X509Certificate> presented;
        try {
            presented = parse(header);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new Refused(RefusedCertificateException.Reason.UNTRUSTED,
                    settings.getHeader() + " does not hold a certificate: " + e.getMessage(), null);
        }
        return validate(presented);
    }

    /**
     * The address of the connection itself. With {@code SERVER_FORWARD_HEADERS_STRATEGY=framework}
     * the request is wrapped and reports the client the proxy names; the trust decision is
     * about the proxy, so this unwraps to the container's own request. (The {@code native}
     * strategy rewrites the address in place, which is why header mode refuses to start with it.)
     */
    static String peerAddress(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current.getRemoteAddr();
    }

    boolean fromTrustedProxy(String address) {
        if (address == null) {
            return false;
        }
        for (String range : settings.trustedProxyList()) {
            try {
                // Within its own address family only: an IPv6 peer is never "in" 10.0.0.0/8.
                if (AddressRange.parse(range).matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                log.warn("ENS_X509_TRUSTED_PROXIES entry '{}' is not an address or CIDR", range);
            }
        }
        return false;
    }

    /** Compared as SHA-256 digests, so the time taken says nothing about the expected value. */
    boolean carriesSecret(HttpServletRequest request) {
        String expected;
        try {
            expected = proxySecret();
        } catch (IllegalStateException e) {
            log.warn(e.getMessage());
            return false;
        }
        String presented = request.getHeader(PROXY_SECRET_HEADER);
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(expected), sha256(presented));
    }

    /** The configured secret: the file's content where one is named, else the value. */
    public String proxySecret() {
        if (settings.getProxySecretFile() != null) {
            try {
                String secret = Files.readString(Path.of(settings.getProxySecretFile()), StandardCharsets.UTF_8).strip();
                return secret.isEmpty() ? null : secret;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "ENS_X509_PROXY_SECRET_FILE " + settings.getProxySecretFile() + " could not be read: "
                                + e.getMessage(), e);
            }
        }
        return settings.getProxySecret();
    }

    /** URL-encoded PEM, PEM, or PEM with its line breaks turned to spaces; the certificate first. */
    static List<X509Certificate> parse(String header) throws GeneralSecurityException {
        String value = header.trim();
        if (value.contains("%")) {
            value = percentDecode(value);
        }
        Matcher pem = PEM.matcher(value);
        List<X509Certificate> certificates = new ArrayList<>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        while (pem.find()) {
            byte[] der = Base64.getMimeDecoder().decode(pem.group(1).replaceAll("\\s", ""));
            certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("no PEM certificate in the header");
        }
        return certificates;
    }

    /**
     * Built to a trust anchor by the platform's path builder, from the certificate and
     * whatever else the header and the CA file hold, as of now. No revocation here - that is
     * the sign-in check's, with its own settings.
     */
    private List<X509Certificate> validate(List<X509Certificate> presented) throws Refused {
        X509Certificate leaf = presented.getFirst();
        String subject = leaf.getSubjectX500Principal().getName();
        if (!forClientAuthentication(leaf)) {
            throw new Refused(RefusedCertificateException.Reason.UNTRUSTED,
                    "'" + subject + "' is not issued for client authentication", leaf);
        }
        List<X509Certificate> cas = trustedCas.certificates();
        try {
            Set<TrustAnchor> anchors = new LinkedHashSet<>();
            for (X509Certificate ca : cas) {
                anchors.add(new TrustAnchor(ca, null));
            }
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(leaf);
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, target);
            parameters.setRevocationEnabled(false);
            List<X509Certificate> intermediates = new ArrayList<>(presented);
            intermediates.addAll(cas);
            parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(intermediates)));
            PKIXCertPathBuilderResult result =
                    (PKIXCertPathBuilderResult) CertPathBuilder.getInstance("PKIX").build(parameters);
            List<X509Certificate> chain = new ArrayList<>();
            result.getCertPath().getCertificates().forEach(c -> chain.add((X509Certificate) c));
            chain.add(result.getTrustAnchor().getTrustedCert());
            return chain;
        } catch (CertPathBuilderException e) {
            throw new Refused(RefusedCertificateException.Reason.UNTRUSTED,
                    "'" + subject + "' does not chain to a CA in ENS_X509_TRUSTED_CA_FILE, or is out of date: "
                            + e.getMessage(), leaf);
        } catch (GeneralSecurityException e) {
            throw new Refused(RefusedCertificateException.Reason.UNTRUSTED,
                    "'" + subject + "' could not be validated: " + e.getMessage(), leaf);
        }
    }

    /** No extended key usage at all, or one that includes client authentication. */
    private static boolean forClientAuthentication(X509Certificate certificate) {
        try {
            List<String> usage = certificate.getExtendedKeyUsage();
            return usage == null || usage.contains(CLIENT_AUTH) || usage.contains(ANY_USAGE);
        } catch (CertificateParsingException e) {
            return false;
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 3986 percent-decoding. Unlike URLDecoder, '+' stays '+', which base64 needs. */
    static String percentDecode(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) | low);
                    i += 2;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
