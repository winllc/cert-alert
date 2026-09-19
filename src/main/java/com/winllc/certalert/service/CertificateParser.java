package com.winllc.certalert.service;

import com.winllc.certalert.config.RiskProperties;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.KeyUsage;
import com.winllc.certalert.revocation.RevocationEndpoints;
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

    private final RiskProperties risk;

    public CertificateParser(RiskProperties risk) {
        this.risk = risk;
    }

    /**
     * Parses one certificate.
     *
     * @throws CertificateParseException if the bytes are not a readable X.509 certificate
     */
    public CachedCertificate parse(byte[] der, Instant cachedAt) {
        X509Certificate certificate = readCertificate(der);
        List<String> dnsNames = dnsNames(certificate);

        CachedCertificate cached = new CachedCertificate(
                CertificateFingerprints.sha256(der),
                certificate.getSerialNumber().toString(16),
                certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                certificate.getSigAlgName(),
                CertificateAlgorithms.hashAlgorithm(certificate),
                certificate.getPublicKey().getAlgorithm(),
                keySize(certificate.getPublicKey()),
                subjectAlternativeNames(dnsNames),
                cachedAt);

        // Assessed from the certificate rather than from what was stored: the stored list is
        // truncated when it is long, and a count taken from it would be short by exactly the
        // names that make it worth flagging.
        SubjectAltNames.Assessment assessment =
                SubjectAltNames.assess(dnsNames, risk.getMaxSubjectAltNames(), risk.getMaxDomains());
        cached.describeNames(assessment.count(), assessment.risks());
        // Which half of a person's credentials this is - the one that signs or the one that
        // is encrypted to - which only the key usage extension can say.
        cached.describeKeyUsage(KeyUsage.of(certificate.getKeyUsage()));
        // Where to ask whether it has been revoked, which only the certificate can say.
        RevocationEndpoints endpoints = RevocationEndpoints.of(certificate);
        cached.describeRevocationEndpoints(
                endpoints.crlUrls(), endpoints.ocspUrl(), RevocationEndpoints.authorityKeyId(certificate));
        return cached;
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

    private String subjectAlternativeNames(List<String> dnsNames) {
        if (dnsNames.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", dnsNames);
        return joined.length() <= MAX_SAN_LENGTH ? joined : joined.substring(0, MAX_SAN_LENGTH - 3) + "...";
    }

    /** The DNS names the certificate is good for, in the order it carries them. */
    private List<String> dnsNames(X509Certificate certificate) {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                return List.of();
            }
            List<String> dnsNames = new ArrayList<>();
            for (List<?> entry : names) {
                if (entry.size() >= 2 && Integer.valueOf(SAN_TYPE_DNS).equals(entry.get(0))) {
                    dnsNames.add(String.valueOf(entry.get(1)));
                }
            }
            return dnsNames;
        } catch (CertificateParsingException e) {
            log.debug("Could not parse subject alternative names", e);
            return List.of();
        }
    }
}
