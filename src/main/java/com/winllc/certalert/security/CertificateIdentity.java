package com.winllc.certalert.security;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import org.springframework.ldap.support.LdapEncoder;

/**
 * What a DoD CAC or PIV certificate says about who holds it, for finding them in the
 * directory: the ENS baseline's {@code {upn}}, {@code {edipi}}, {@code {email}} and
 * {@code {uuid}}.
 *
 * <ul>
 *   <li>{@code {upn}} - the subject alternative name's UserPrincipalName (otherName
 *       1.3.6.1.4.1.311.20.2.3), e.g. {@code 1234567890@mil}.
 *   <li>{@code {edipi}} - the DoD EDIPI: the digits before {@code @} in a UPN of the form
 *       {@code 1234567890@mil}, or else the trailing number of a subject CN of the form
 *       {@code LAST.FIRST.MI.1234567890}.
 *   <li>{@code {email}} - the rfc822Name alternative name, or else the subject's
 *       emailAddress.
 *   <li>{@code {uuid}} - the PIV FASC-N UUID: an alternative name URI {@code urn:uuid:...}.
 * </ul>
 *
 * <p>Each value is escaped for an LDAP filter where it is put into one, so a certificate
 * cannot widen the search it is looked up by.
 */
public final class CertificateIdentity {

    private static final int SAN_OTHER_NAME = 0;
    private static final int SAN_RFC822_NAME = 1;
    private static final int SAN_URI = 6;

    /** 1.3.6.1.4.1.311.20.2.3, Microsoft's UserPrincipalName, as its DER content bytes. */
    private static final byte[] UPN_OID = {0x2B, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x14, 0x02, 0x03};

    private static final String EMAIL_ADDRESS_OID = "1.2.840.113549.1.9.1";

    private static final Pattern EDIPI_UPN = Pattern.compile("^(\\d{10})@mil$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EDIPI_CN = Pattern.compile("\\.(\\d{10})$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(upn|edipi|email|uuid)}");

    private final String upn;
    private final String edipi;
    private final String email;
    private final String uuid;

    private CertificateIdentity(String upn, String edipi, String email, String uuid) {
        this.upn = upn;
        this.edipi = edipi;
        this.email = email;
        this.uuid = uuid;
    }

    public static CertificateIdentity of(X509Certificate certificate) {
        String upn = null;
        String rfc822 = null;
        String uuid = null;
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> name : names) {
                    if (name.size() < 2 || !(name.get(0) instanceof Integer type)) {
                        continue;
                    }
                    if (type == SAN_OTHER_NAME && upn == null && name.get(1) instanceof byte[] der) {
                        upn = userPrincipalName(der);
                    } else if (type == SAN_RFC822_NAME && rfc822 == null) {
                        rfc822 = String.valueOf(name.get(1)).trim();
                    } else if (type == SAN_URI && uuid == null) {
                        String uri = String.valueOf(name.get(1)).trim();
                        if (uri.toLowerCase(Locale.ROOT).startsWith("urn:uuid:")) {
                            uuid = uri.substring("urn:uuid:".length());
                        }
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // A certificate whose alternative names will not parse has none to offer.
        }
        String commonName = subjectAttribute(certificate, "CN");
        String edipi = null;
        if (upn != null) {
            Matcher fromUpn = EDIPI_UPN.matcher(upn);
            if (fromUpn.matches()) {
                edipi = fromUpn.group(1);
            }
        }
        if (edipi == null && commonName != null) {
            Matcher fromCn = EDIPI_CN.matcher(commonName.trim());
            if (fromCn.find()) {
                edipi = fromCn.group(1);
            }
        }
        String email = rfc822 != null ? rfc822 : subjectAttribute(certificate, "EMAILADDRESS");
        return new CertificateIdentity(blank(upn), edipi, blank(email), blank(uuid));
    }

    public Optional<String> upn() {
        return Optional.ofNullable(upn);
    }

    public Optional<String> edipi() {
        return Optional.ofNullable(edipi);
    }

    public Optional<String> email() {
        return Optional.ofNullable(email);
    }

    public Optional<String> uuid() {
        return Optional.ofNullable(uuid);
    }

    /**
     * A filter with this certificate's values put in, each escaped for an LDAP filter.
     *
     * @return empty where the filter names a value the certificate does not carry - a filter
     *     with a hole in it would match something else, so it is not used at all
     */
    public Optional<String> fill(String template) {
        Map<String, String> values = new java.util.HashMap<>();
        values.put("upn", upn);
        values.put("edipi", edipi);
        values.put("email", email);
        values.put("uuid", uuid);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                return Optional.empty();
            }
            matcher.appendReplacement(filled, Matcher.quoteReplacement(LdapEncoder.filterEncode(value)));
        }
        matcher.appendTail(filled);
        return Optional.of(filled.toString());
    }

    /** Which placeholders a template uses that this certificate cannot fill, for saying why. */
    public List<String> missing(String template) {
        List<String> missing = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean present = switch (name) {
                case "upn" -> upn != null;
                case "edipi" -> edipi != null;
                case "email" -> email != null;
                default -> uuid != null;
            };
            if (!present && !missing.contains(name)) {
                missing.add("{" + name + "}");
            }
        }
        return missing;
    }

    @Override
    public String toString() {
        return "upn=" + upn + ", edipi=" + edipi + ", email=" + email + ", uuid=" + uuid;
    }

    /**
     * OtherName ::= SEQUENCE { type-id OBJECT IDENTIFIER, value [0] EXPLICIT ANY } - the UPN
     * when the type is UserPrincipalName and the value a UTF8String.
     */
    static String userPrincipalName(byte[] der) {
        List<Tlv> outer = Tlv.parse(der);
        // The platform hands over the OtherName's SEQUENCE; tagged as the GeneralName [0]
        // is the same content.
        if (outer.size() != 1 || (outer.getFirst().tag() != 0x30 && outer.getFirst().tag() != 0xA0)) {
            return null;
        }
        List<Tlv> fields = Tlv.parse(outer.getFirst().value());
        if (fields.size() < 2 || fields.get(0).tag() != 0x06 || !java.util.Arrays.equals(fields.get(0).value(), UPN_OID)) {
            return null;
        }
        Tlv explicit = fields.get(1);
        if (explicit.tag() != 0xA0) {
            return null;
        }
        List<Tlv> inner = Tlv.parse(explicit.value());
        if (inner.isEmpty()) {
            return null;
        }
        // UTF8String, or the IA5String and PrintableString some issuers use instead.
        int tag = inner.getFirst().tag();
        if (tag != 0x0C && tag != 0x16 && tag != 0x13) {
            return null;
        }
        return new String(inner.getFirst().value(), StandardCharsets.UTF_8).trim();
    }

    private static String subjectAttribute(X509Certificate certificate, String type) {
        String subject = certificate.getSubjectX500Principal()
                .getName(X500Principal.RFC2253, Map.of(EMAIL_ADDRESS_OID, "EMAILADDRESS"));
        try {
            List<Rdn> rdns = new LdapName(subject).getRdns();
            // LdapName lists the most significant last; the CN that names the person is the
            // most specific, so read from the end.
            for (int i = rdns.size() - 1; i >= 0; i--) {
                if (rdns.get(i).getType().equalsIgnoreCase(type)) {
                    return String.valueOf(rdns.get(i).getValue());
                }
            }
        } catch (InvalidNameException e) {
            // no subject attribute to offer
        }
        return null;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Just enough DER for an OtherName. */
    private record Tlv(int tag, byte[] value) {

        static List<Tlv> parse(byte[] der) {
            List<Tlv> values = new ArrayList<>();
            int at = 0;
            while (at + 2 <= der.length) {
                int tag = der[at++] & 0xFF;
                int length = der[at++] & 0xFF;
                if ((length & 0x80) != 0) {
                    int octets = length & 0x7F;
                    if (octets == 0 || octets > 3 || at + octets > der.length) {
                        return values;
                    }
                    length = 0;
                    for (int i = 0; i < octets; i++) {
                        length = (length << 8) | (der[at++] & 0xFF);
                    }
                }
                if (at + length > der.length) {
                    return values;
                }
                values.add(new Tlv(tag, java.util.Arrays.copyOfRange(der, at, at + length)));
                at += length;
            }
            return values;
        }
    }
}
