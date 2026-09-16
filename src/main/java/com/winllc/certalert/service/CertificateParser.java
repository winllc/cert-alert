package com.winllc.certalert.service;

import com.winllc.certalert.domain.CachedCertificate;
import java.io.ByteArrayInputStream;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the DER bytes of a directory's certificate attribute into the details worth
 * caching.
 *
 * <p>The SHA-256 fingerprint of the DER encoding is the identity of a cached certificate.
 * It is what lets a later sync recognise a certificate it already holds, so re-syncing an
 * unchanged directory does no writes.
 */
@Component
public class CertificateParser {

    private static final Logger log = LoggerFactory.getLogger(CertificateParser.class);

    private static final int SAN_TYPE_DNS = 2;
    private static final int MAX_SAN_LENGTH = 2000;

    /**
     * Parses one certificate.
     *
     * @throws CertificateParseException if the bytes are not a readable X.509 certificate
     */
    public CachedCertificate parse(byte[] der, Instant cachedAt) {
        X509Certificate certificate = readCertificate(der);
        return new CachedCertificate(
                CertificateFingerprints.sha256(der),
                certificate.getSerialNumber().toString(16),
                certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                certificate.getSigAlgName(),
                certificate.getPublicKey().getAlgorithm(),
                keySize(certificate.getPublicKey()),
                subjectAlternativeNames(certificate),
                cachedAt);
    }

    private X509Certificate readCertificate(byte[] der) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException | ClassCastException e) {
            throw new CertificateParseException("Attribute did not contain a readable X.509 certificate", e);
        }
    }

    private Integer keySize(PublicKey key) {
        return switch (key) {
            case RSAPublicKey rsa -> rsa.getModulus().bitLength();
            case DSAPublicKey dsa -> dsa.getParams().getP().bitLength();
            case ECPublicKey ec -> ec.getParams().getCurve().getField().getFieldSize();
            default -> null;
        };
    }

    private String subjectAlternativeNames(X509Certificate certificate) {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                return null;
            }
            List<String> dnsNames = new ArrayList<>();
            for (List<?> entry : names) {
                if (entry.size() >= 2 && Integer.valueOf(SAN_TYPE_DNS).equals(entry.get(0))) {
                    dnsNames.add(String.valueOf(entry.get(1)));
                }
            }
            if (dnsNames.isEmpty()) {
                return null;
            }
            String joined = String.join(", ", dnsNames);
            return joined.length() <= MAX_SAN_LENGTH ? joined : joined.substring(0, MAX_SAN_LENGTH - 3) + "...";
        } catch (CertificateParsingException e) {
            log.debug("Could not parse subject alternative names", e);
            return null;
        }
    }
}
