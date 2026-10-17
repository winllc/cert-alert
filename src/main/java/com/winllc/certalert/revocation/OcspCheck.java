package com.winllc.certalert.revocation;

import com.winllc.certalert.domain.RevocationMethod;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateRevokedException;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One question to an OCSP responder about one certificate.
 *
 * <p>Split in two on purpose. The asking is done here, through a {@link RevocationFetcher},
 * so the responder's address is held to the same rules as a CRL's and the wait has the
 * caller's own timeouts. The answer is checked by the platform's path validator, handed the
 * response the way it would be handed a stapled one: its signature, whether whoever signed
 * it may speak for the issuer, whether it is about this certificate and whether it is
 * current are all ASN.1 and cryptography that the JDK already does correctly - and a
 * hand-rolled check that got one of them subtly wrong would accept a forged "good"
 * without ever looking broken.
 *
 * <p>Nothing here touches a system property. The platform's own OCSP client reads its
 * timeout from one, once, for the whole process; asking through the fetcher is what lets
 * sign-in wait five seconds while the nightly job waits thirty.
 */
final class OcspCheck {

    /** SHA-1, which is what the platform names the certificate by when it checks the answer. */
    private static final byte[] SHA1_ALGORITHM = {
        0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E, 0x03, 0x02, 0x1A, 0x05, 0x00
    };

    private OcspCheck() {}

    /**
     * Asks, and checks the answer.
     *
     * @param where how the result describes the responder
     * @param at the moment the answer has to be current for
     */
    static RevocationChecker.Outcome ask(
            RevocationFetcher fetcher,
            X509Certificate leaf,
            X509Certificate issuer,
            String responder,
            String where,
            Date at) {
        byte[] answer;
        try {
            answer = fetcher.post(
                    responder, "application/ocsp-request", request(leaf, issuer), "application/ocsp-response");
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return RevocationChecker.Outcome.unknown("OCSP could not be asked: " + describe(e));
        }
        try {
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            PKIXRevocationChecker checker = (PKIXRevocationChecker) validator.getRevocationChecker();
            // The end entity only, and no falling back to a list: the caller does that
            // itself, with the lists it already holds.
            checker.setOptions(EnumSet.of(
                    PKIXRevocationChecker.Option.ONLY_END_ENTITY, PKIXRevocationChecker.Option.NO_FALLBACK));
            checker.setOcspResponder(URI.create(responder));
            checker.setOcspResponses(Map.of(leaf, answer));

            PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(issuer, null)));
            // The explicit checker does the asking; the built-in one would ask again.
            parameters.setRevocationEnabled(false);
            parameters.addCertPathChecker(checker);
            parameters.setDate(at);

            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf));
            validator.validate(path, parameters);
            return RevocationChecker.Outcome.good(RevocationMethod.OCSP, where);
        } catch (CertPathValidatorException e) {
            if (e.getCause() instanceof CertificateRevokedException revoked) {
                return RevocationChecker.Outcome.revoked(
                        RevocationMethod.OCSP,
                        revoked.getRevocationDate().toInstant(),
                        revoked.getRevocationReason() == null ? null : revoked.getRevocationReason().name(),
                        where);
            }
            if (e.getReason() == CertPathValidatorException.BasicReason.REVOKED) {
                return RevocationChecker.Outcome.revoked(RevocationMethod.OCSP, null, null, where);
            }
            // The path is one certificate under its own issuer, so the other ways it can fail
            // - an answer signed by the wrong key, a stale one, an expired leaf - mean the
            // question went unanswered rather than answered badly.
            return RevocationChecker.Outcome.unknown("OCSP gave no usable answer: " + describe(e));
        } catch (GeneralSecurityException | RuntimeException e) {
            return RevocationChecker.Outcome.unknown("OCSP could not be asked: " + describe(e));
        }
    }

    /**
     * An OCSPRequest (RFC 6960) about one certificate, unsigned and without a nonce:
     *
     * <pre>
     * OCSPRequest ::= SEQUENCE { tbsRequest SEQUENCE { requestList SEQUENCE OF
     *     SEQUENCE { reqCert CertID } } }
     * CertID ::= SEQUENCE { hashAlgorithm, issuerNameHash, issuerKeyHash, serialNumber }
     * </pre>
     *
     * Named by SHA-1 hashes of the issuer's name and key, which is the identifier the
     * platform compares the answer's against.
     */
    static byte[] request(X509Certificate leaf, X509Certificate issuer) throws GeneralSecurityException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] nameHash = sha1.digest(issuer.getSubjectX500Principal().getEncoded());
        byte[] keyHash = sha1.digest(subjectPublicKeyBits(issuer));
        byte[] certId = sequence(
                SHA1_ALGORITHM,
                tlv(0x04, nameHash),
                tlv(0x04, keyHash),
                tlv(0x02, leaf.getSerialNumber().toByteArray()));
        byte[] request = sequence(certId);
        byte[] requestList = sequence(request);
        byte[] tbsRequest = sequence(requestList);
        return sequence(tbsRequest);
    }

    /** The key itself: the BIT STRING's contents in the SubjectPublicKeyInfo, less its pad count. */
    private static byte[] subjectPublicKeyBits(X509Certificate certificate) throws GeneralSecurityException {
        List<Der.Tlv> info = Der.parse(certificate.getPublicKey().getEncoded());
        if (info.size() == 1) {
            List<Der.Tlv> fields = info.getFirst().children();
            if (fields.size() == 2 && fields.get(1).tag() == 0x03 && fields.get(1).value().length > 0) {
                byte[] bits = fields.get(1).value();
                byte[] key = new byte[bits.length - 1];
                System.arraycopy(bits, 1, key, 0, key.length);
                return key;
            }
        }
        throw new GeneralSecurityException("The issuer's public key could not be read");
    }

    private static byte[] sequence(byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            content.writeBytes(part);
        }
        return tlv(0x30, content.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int octets = length > 0xFFFFFF ? 4 : length > 0xFFFF ? 3 : length > 0xFF ? 2 : 1;
            out.write(0x80 | octets);
            for (int i = octets - 1; i >= 0; i--) {
                out.write((length >>> (8 * i)) & 0xFF);
            }
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
