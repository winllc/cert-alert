package com.winllc.certalert.revocation;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where a certificate says to ask whether it has been revoked.
 *
 * <p>Both answers are carried by the certificate itself and by nothing else, so they are
 * read when it is parsed and cached with it. Without them a revocation check has nowhere to
 * go: the serial number is known, and the responder that would recognise it is not.
 *
 * @param crlUrls the CRL distribution points, in the order the certificate lists them
 * @param ocspUrl the OCSP responder from the authority information access, or null
 */
public record RevocationEndpoints(List<String> crlUrls, String ocspUrl) {

    public static final RevocationEndpoints NONE = new RevocationEndpoints(List.of(), null);

    private static final String CRL_DISTRIBUTION_POINTS = "2.5.29.31";
    private static final String AUTHORITY_INFO_ACCESS = "1.3.6.1.5.5.7.1.1";
    private static final String AUTHORITY_KEY_IDENTIFIER = "2.5.29.35";

    /** {@code id-ad-ocsp}, the access method that marks a responder rather than a CA issuer. */
    private static final byte[] ID_AD_OCSP = {0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x01};

    /** A URI this application could actually fetch. */
    private static final List<String> FETCHABLE = List.of("http://", "https://", "ldap://", "ldaps://");

    public static RevocationEndpoints of(X509Certificate certificate) {
        return new RevocationEndpoints(crlUrls(certificate), ocspUrl(certificate));
    }

    public boolean isEmpty() {
        return crlUrls.isEmpty() && ocspUrl == null;
    }

    /**
     * The issuer's key identifier, which is what names the issuer to look for when several
     * CAs share a name - a CA that has been re-keyed being the usual reason. Hex, or null.
     */
    public static String authorityKeyId(X509Certificate certificate) {
        // AuthorityKeyIdentifier ::= SEQUENCE { keyIdentifier [0] OCTET STRING OPTIONAL, ... }
        List<byte[]> identifiers = Der.collectContext(
                Der.unwrapExtension(certificate.getExtensionValue(AUTHORITY_KEY_IDENTIFIER)), 0);
        return identifiers.isEmpty() ? null : hex(identifiers.getFirst());
    }

    /** The subject key identifier of a CA certificate, which is what the above points at. */
    public static String subjectKeyId(X509Certificate certificate) {
        byte[] extension = certificate.getExtensionValue("2.5.29.14");
        if (extension == null) {
            return null;
        }
        // SubjectKeyIdentifier is an OCTET STRING, itself wrapped in the extension's.
        List<Der.Tlv> inner = Der.unwrapExtension(extension);
        return inner.size() == 1 ? hex(inner.getFirst().value()) : null;
    }

    private static List<String> crlUrls(X509Certificate certificate) {
        // Every URI in the extension: a distribution point's fullName is a GeneralNames, and
        // the layers of optional structure above it differ between issuers.
        List<String> urls = new ArrayList<>();
        for (byte[] uri : Der.collectContext(
                Der.unwrapExtension(certificate.getExtensionValue(CRL_DISTRIBUTION_POINTS)), 6)) {
            String url = text(uri);
            if (fetchable(url) && !urls.contains(url)) {
                urls.add(url);
            }
        }
        return List.copyOf(urls);
    }

    /**
     * The OCSP responder, which needs more care than the CRL URLs: an authority information
     * access lists CA issuers as well, with the same kind of URI, and fetching a CA
     * certificate is not asking anybody about revocation. The access method OID is what
     * separates them, so each description is read rather than scanned.
     */
    private static String ocspUrl(X509Certificate certificate) {
        for (Der.Tlv description : descriptions(certificate)) {
            List<Der.Tlv> parts = description.children();
            if (parts.size() < 2 || !isOcsp(parts.getFirst())) {
                continue;
            }
            Der.Tlv location = parts.get(1);
            if (location.isContext(6) && !location.isConstructed()) {
                String url = text(location.value());
                if (fetchable(url)) {
                    return url;
                }
            }
        }
        return null;
    }

    private static List<Der.Tlv> descriptions(X509Certificate certificate) {
        List<Der.Tlv> extension = Der.unwrapExtension(certificate.getExtensionValue(AUTHORITY_INFO_ACCESS));
        // AuthorityInfoAccessSyntax ::= SEQUENCE OF AccessDescription
        return extension.size() == 1 ? extension.getFirst().children() : List.of();
    }

    private static boolean isOcsp(Der.Tlv method) {
        return method.number() == 6 && java.util.Arrays.equals(method.value(), ID_AD_OCSP);
    }

    private static boolean fetchable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return FETCHABLE.stream().anyMatch(lower::startsWith);
    }

    private static String text(byte[] ia5) {
        return new String(ia5, StandardCharsets.US_ASCII).trim();
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
