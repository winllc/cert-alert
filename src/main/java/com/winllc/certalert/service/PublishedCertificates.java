package com.winllc.certalert.service;

import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.ldap.LdapCertificateStore;
import com.winllc.certalert.ldap.LdapProperties;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads an entry's certificates back out of the directory, by fingerprint.
 *
 * <p>The cache holds what is worth querying about a certificate and not the certificate:
 * a hundred thousand entries' worth of DER is a quarter of a gigabyte that every table
 * query would drag behind it. Almost everything here works from the cached details, which
 * is the point of caching them.
 *
 * <p>OCSP is the exception. A request names the certificate being asked about by its
 * serial number and by hashes of its issuer's name and key - and the platform's checker,
 * which is what forms that request, works from a certification path and so wants the
 * certificate itself. So the bytes have to come from somewhere, and the directory is where
 * they are. One read per entry, and only for entries whose certificates are going to be
 * asked about over OCSP.
 *
 * <p>The same shape the cleanup job uses for the same reason: matched by fingerprint rather
 * than by position, because the order an attribute's values come back in is the directory's
 * business and the entry may have gained or lost one since the last sweep.
 */
@Component
public class PublishedCertificates {

    private static final Logger log = LoggerFactory.getLogger(PublishedCertificates.class);

    private final LdapCertificateStore store;
    private final LdapProperties properties;

    public PublishedCertificates(LdapCertificateStore store, LdapProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * What this entry publishes, keyed by SHA-256 fingerprint of the DER.
     *
     * <p>Empty where the entry could not be read or holds nothing readable. That is not an
     * error here: the caller has a second way to ask, and a result that says what it could
     * not do is worth more than an exception out of a nightly job.
     */
    public Map<String, X509Certificate> of(DirectoryEntry entry, OwnerType type) {
        String attribute = type == OwnerType.USER
                ? properties.getUser().getCertificate()
                : properties.getServer().getCertificate();

        Map<String, X509Certificate> found = new HashMap<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (byte[] der : store.read(entry.getDn(), attribute)) {
                try {
                    X509Certificate certificate = (X509Certificate)
                            factory.generateCertificate(new ByteArrayInputStream(der));
                    found.put(CertificateFingerprints.sha256(der), certificate);
                } catch (java.security.cert.CertificateException e) {
                    // One unreadable value is not a reason to lose the rest of them; the
                    // sweep will have said the same about it already.
                    log.debug("Unreadable certificate value on '{}'", entry.getDn());
                }
            }
        } catch (java.security.cert.CertificateException | RuntimeException e) {
            log.warn("Could not read the certificates of '{}' for an OCSP check: {}",
                    entry.getDn(), e.getMessage());
            return Map.of();
        }
        return found;
    }
}
