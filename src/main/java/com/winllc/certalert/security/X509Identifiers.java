package com.winllc.certalert.security;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls out of a certificate every value that might name its holder in the directory.
 *
 * <p>Used only when a certificate is not one the directory publishes, as the weaker
 * fallback to matching it by fingerprint.
 */
final class X509Identifiers {

    private static final Logger log = LoggerFactory.getLogger(X509Identifiers.class);

    private static final int SAN_TYPE_RFC822_NAME = 1;

    /** Subject attributes that plausibly name a person rather than describe them. */
    private static final Set<String> NAMING_ATTRIBUTES = Set.of("cn", "uid", "emailaddress", "e", "mail");

    private X509Identifiers() {}

    /** Lowercased candidate identifiers, in the order they were found. */
    static Set<String> of(X509Certificate certificate) {
        Set<String> identifiers = new LinkedHashSet<>();
        addSubjectAttributes(certificate, identifiers);
        addEmailAlternativeNames(certificate, identifiers);
        return identifiers;
    }

    private static void addSubjectAttributes(X509Certificate certificate, Collection<String> identifiers) {
        String subject = certificate.getSubjectX500Principal().getName();
        try {
            LdapName name = new LdapName(subject);
            for (Rdn rdn : name.getRdns()) {
                if (NAMING_ATTRIBUTES.contains(rdn.getType().toLowerCase(Locale.ROOT))) {
                    add(identifiers, String.valueOf(rdn.getValue()));
                }
            }
        } catch (InvalidNameException e) {
            log.debug("Could not parse certificate subject '{}'", subject, e);
        }
    }

    private static void addEmailAlternativeNames(X509Certificate certificate, Collection<String> identifiers) {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                return;
            }
            for (List<?> entry : names) {
                if (entry.size() >= 2 && Integer.valueOf(SAN_TYPE_RFC822_NAME).equals(entry.get(0))) {
                    add(identifiers, String.valueOf(entry.get(1)));
                }
            }
        } catch (CertificateParsingException e) {
            log.debug("Could not parse subject alternative names", e);
        }
    }

    private static void add(Collection<String> identifiers, String value) {
        if (value != null && !value.isBlank()) {
            identifiers.add(value.trim().toLowerCase(Locale.ROOT));
        }
    }
}
