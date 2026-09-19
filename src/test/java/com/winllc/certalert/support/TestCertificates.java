package com.winllc.certalert.support;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Mints certificates with exact validity windows, so tests can state "expired three days
 * ago" or "expires in five days" instead of depending on fixtures that rot.
 */
public final class TestCertificates {

    private static final AtomicLong SERIAL = new AtomicLong(1);
    private static final KeyPair KEY_PAIR = generateKeyPair();

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private TestCertificates() {}

    /** A certificate that expired {@code ago} before now. */
    public static byte[] expired(String commonName, Duration ago) {
        Instant now = Instant.now();
        return der(commonName, now.minus(ago).minus(Duration.ofDays(365)), now.minus(ago));
    }

    /** A certificate that expires {@code remaining} from now. */
    public static byte[] expiringIn(String commonName, Duration remaining) {
        Instant now = Instant.now();
        return der(commonName, now.minus(Duration.ofDays(30)), now.plus(remaining));
    }

    /**
     * The signing half of a person's credentials: digital signature and non-repudiation,
     * and nothing that would let it be encrypted to.
     */
    public static byte[] signing(String commonName, Instant notBefore, Instant notAfter) {
        return der(commonName, notBefore, notAfter,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
    }

    /** The other half: key encipherment, which is what a CA escrows the private key of. */
    public static byte[] encryption(String commonName, Instant notBefore, Instant notAfter) {
        return der(commonName, notBefore, notAfter, new KeyUsage(KeyUsage.keyEncipherment));
    }

    /** What a server usually holds: one certificate that does both. */
    public static byte[] dual(String commonName, Instant notBefore, Instant notAfter) {
        return der(commonName, notBefore, notAfter,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    }

    private static byte[] der(String commonName, Instant notBefore, Instant notAfter, KeyUsage keyUsage) {
        try {
            return certificate(
                            commonName,
                            notBefore,
                            notAfter,
                            "SHA256withRSA",
                            KEY_PAIR,
                            new String[] {commonName},
                            keyUsage)
                    .getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Could not encode test certificate", e);
        }
    }

    public static byte[] der(String commonName, Instant notBefore, Instant notAfter) {
        try {
            return certificate(commonName, notBefore, notAfter).getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Could not encode test certificate", e);
        }
    }

    public static X509Certificate certificate(String commonName, Instant notBefore, Instant notAfter) {
        return certificate(commonName, notBefore, notAfter, "SHA256withRSA", KEY_PAIR);
    }

    /**
     * A certificate signed with a named algorithm, and optionally a key of another kind -
     * for the tests about what is cached <em>about</em> a certificate rather than when it
     * expires.
     */
    public static byte[] der(String commonName, String signatureAlgorithm, KeyPair keyPair) {
        try {
            Instant now = Instant.now();
            return certificate(
                            commonName,
                            now.minus(Duration.ofDays(1)),
                            now.plus(Duration.ofDays(365)),
                            signatureAlgorithm,
                            keyPair == null ? KEY_PAIR : keyPair)
                    .getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Could not encode test certificate", e);
        }
    }

    /**
     * A certificate good for several names, for the tests about what those names say: a
     * wildcard, a batch of hosts, a name with no domain.
     */
    public static byte[] withNames(String commonName, String... dnsNames) {
        try {
            Instant now = Instant.now();
            return certificate(
                            commonName,
                            now.minus(Duration.ofDays(1)),
                            now.plus(Duration.ofDays(365)),
                            "SHA256withRSA",
                            KEY_PAIR,
                            dnsNames)
                    .getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Could not encode test certificate", e);
        }
    }

    /**
     * The key behind every certificate minted here, so a test can serve one over TLS as
     * well as publish it. One key for all of them is deliberate - key generation is the
     * slow part - and means any certificate from this class can be served by any server.
     */
    public static KeyPair sharedKeyPair() {
        return KEY_PAIR;
    }

    /** A key pair of a given kind, for the same reason. */
    public static KeyPair keyPair(String algorithm, int size) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm, BouncyCastleProvider.PROVIDER_NAME);
            generator.initialize(size);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate a " + algorithm + " key pair", e);
        }
    }

    private static X509Certificate certificate(
            String commonName, Instant notBefore, Instant notAfter, String signatureAlgorithm, KeyPair keyPair) {
        return certificate(commonName, notBefore, notAfter, signatureAlgorithm, keyPair, new String[] {commonName});
    }

    private static X509Certificate certificate(
            String commonName,
            Instant notBefore,
            Instant notAfter,
            String signatureAlgorithm,
            KeyPair keyPair,
            String[] dnsNames) {
        return certificate(commonName, notBefore, notAfter, signatureAlgorithm, keyPair, dnsNames, null);
    }

    private static X509Certificate certificate(
            String commonName,
            Instant notBefore,
            Instant notAfter,
            String signatureAlgorithm,
            KeyPair keyPair,
            String[] dnsNames,
            KeyUsage keyUsage) {
        try {
            X500Name name = new X500Name("CN=" + commonName);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.valueOf(SERIAL.getAndIncrement()),
                    Date.from(notBefore),
                    Date.from(notAfter),
                    name,
                    keyPair.getPublic());
            GeneralName[] names = new GeneralName[dnsNames.length];
            for (int i = 0; i < dnsNames.length; i++) {
                names[i] = new GeneralName(GeneralName.dNSName, dnsNames[i]);
            }
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
            if (keyUsage != null) {
                builder.addExtension(Extension.keyUsage, true, keyUsage);
            }
            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(keyPair.getPrivate());
            return new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(builder.build(signer));
        } catch (OperatorCreationException | IOException | java.security.cert.CertificateException e) {
            throw new IllegalStateException("Could not build test certificate", e);
        }
    }

    /** Rebuilds a certificate from the DER that was handed to the directory. */
    public static X509Certificate certificateOf(byte[] der) {
        try {
            return (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (java.security.cert.CertificateException e) {
            throw new IllegalStateException("Could not read back a test certificate", e);
        }
    }

    /** The fingerprint the application will cache this certificate under. */
    public static String sha256(byte[] der) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform but was unavailable", e);
        }
    }

    /** One key pair for every test certificate: key generation is the slow part, not signing. */
    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is required by the platform but was unavailable", e);
        }
    }
}
