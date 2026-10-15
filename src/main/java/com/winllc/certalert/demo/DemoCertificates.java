package com.winllc.certalert.demo;

import com.unboundid.asn1.ASN1BitString;
import com.unboundid.asn1.ASN1Element;
import com.unboundid.asn1.ASN1Sequence;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.util.OID;
import com.unboundid.util.ObjectPair;
import com.unboundid.util.ssl.cert.CertException;
import com.unboundid.util.ssl.cert.PublicKeyAlgorithmIdentifier;
import com.unboundid.util.ssl.cert.SignatureAlgorithmIdentifier;
import com.unboundid.util.ssl.cert.X509Certificate;
import com.unboundid.util.ssl.cert.X509CertificateExtension;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mints the demo's certificates, from a throwaway authority made at startup.
 *
 * <p>Generated rather than shipped as a fixture because the one thing a demo of an expiry
 * tracker cannot do is show a directory that has rotted. Every window here is measured
 * from the moment the application started, so "expires in nine days" still means nine days
 * on an image built last spring.
 *
 * <p>One key pair, reused by every certificate including the authority's. Generating a
 * couple of hundred RSA keys is most of a minute of startup, and none of what the demo
 * shows depends on the keys differing - it reads names, dates and usages. Nothing here is
 * a secret and nothing here authenticates anybody: the authority is thrown away with the
 * process, and no deployment trusts it.
 *
 * <p>Uses the LDAP SDK's own certificate support rather than a crypto library, since the
 * SDK is already here to serve the directory. Key usage and subject alternative names are
 * encoded by hand because the SDK keeps those extension classes to itself, and the demo
 * needs both: key usage is what tells a person's signing half from their encryption half,
 * and the alternative names are what the risky-name flags read.
 */
final class DemoCertificates {

    /** 2.5.29.15 - which operations a key is allowed to perform. */
    private static final OID KEY_USAGE = new OID("2.5.29.15");

    /** 2.5.29.17 - the other names the subject answers to. */
    private static final OID SUBJECT_ALTERNATIVE_NAME = new OID("2.5.29.17");

    /** Context tag [1]: an rfc822Name, which is to say an email address. */
    private static final byte RFC_822_NAME = (byte) 0x81;

    /** Context tag [2]: a dNSName. */
    private static final byte DNS_NAME = (byte) 0x82;

    /** What a certificate is for, in the terms the key usage bits state it. */
    enum Use {
        /** The signing half of a person's pair: digital signature and non-repudiation. */
        SIGNING,
        /** The encryption half: key encipherment, and nothing that could sign. */
        ENCRYPTION,
        /** A server, which does both. */
        SERVER
    }

    private final AtomicLong serial = new AtomicLong(1);
    private final KeyPair keyPair;
    private final X509Certificate authority;

    DemoCertificates() {
        try {
            ObjectPair<X509Certificate, KeyPair> ca = X509Certificate.generateSelfSignedCertificate(
                    SignatureAlgorithmIdentifier.SHA_256_WITH_RSA,
                    PublicKeyAlgorithmIdentifier.RSA,
                    2048,
                    new DN("CN=Demo Issuing CA,O=Example Agency,C=US"),
                    Instant.now().minusSeconds(3650L * 86400).getEpochSecond() * 1000L,
                    Instant.now().plusSeconds(3650L * 86400).getEpochSecond() * 1000L);
            this.authority = ca.getFirst();
            this.keyPair = ca.getSecond();
        } catch (CertException | LDAPException e) {
            throw new IllegalStateException("Could not make the demo's certificate authority", e);
        }
    }

    /**
     * A certificate for this subject, valid over exactly this window.
     *
     * @param alternativeNames DNS names for a server, addresses for a person; may be empty
     */
    byte[] issue(String subjectDn, Instant notBefore, Instant notAfter, Use use, List<String> alternativeNames) {
        try {
            List<X509CertificateExtension> extensions = new ArrayList<>();
            extensions.add(keyUsageOf(use));
            if (!alternativeNames.isEmpty()) {
                extensions.add(alternativeNamesOf(use, alternativeNames));
            }

            X509Certificate certificate = X509Certificate.generateIssuerSignedCertificate(
                    SignatureAlgorithmIdentifier.SHA_256_WITH_RSA,
                    authority,
                    keyPair.getPrivate(),
                    // The same key in every certificate, taken back off the authority's
                    // own, rather than generating one per subject.
                    authority.getPublicKeyAlgorithmOID(),
                    authority.getPublicKeyAlgorithmParameters(),
                    authority.getEncodedPublicKey(),
                    authority.getDecodedPublicKey(),
                    new DN(subjectDn),
                    notBefore.getEpochSecond() * 1000L,
                    notAfter.getEpochSecond() * 1000L,
                    extensions.toArray(X509CertificateExtension[]::new));
            return certificate.getX509CertificateBytes();
        } catch (CertException | LDAPException e) {
            throw new IllegalStateException("Could not mint a demo certificate for " + subjectDn, e);
        }
    }

    /**
     * The key usage bits, in the order the extension defines them: digitalSignature,
     * nonRepudiation, keyEncipherment, dataEncipherment, keyAgreement, keyCertSign,
     * cRLSign, encipherOnly, decipherOnly.
     *
     * <p>A person's two certificates differ here and nowhere else that matters, which is
     * exactly what the application reads to tell one from the other.
     */
    private static X509CertificateExtension keyUsageOf(Use use) {
        ASN1BitString bits = switch (use) {
            case SIGNING -> new ASN1BitString(true, true, false, false, false);
            case ENCRYPTION -> new ASN1BitString(false, false, true, true, false);
            case SERVER -> new ASN1BitString(true, false, true, false, false);
        };
        return new X509CertificateExtension(KEY_USAGE, true, bits.encode());
    }

    /** A DNS name for a server, an address for a person - which is what each publishes. */
    private static X509CertificateExtension alternativeNamesOf(Use use, List<String> names) {
        byte tag = use == Use.SERVER ? DNS_NAME : RFC_822_NAME;
        List<ASN1Element> elements = new ArrayList<>();
        for (String name : names) {
            elements.add(new ASN1Element(tag, name.getBytes(StandardCharsets.UTF_8)));
        }
        return new X509CertificateExtension(
                SUBJECT_ALTERNATIVE_NAME, false, new ASN1Sequence(elements).encode());
    }

    /** The serial for the next certificate, so each one differs. */
    long nextSerial() {
        return serial.getAndIncrement();
    }
}
