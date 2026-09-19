package com.winllc.certalert.support;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A throwaway certificate authority: issues certificates that name where to ask about
 * their revocation, and publishes the list that answers.
 *
 * <p>Both halves are needed together. A revocation check reads the distribution point out
 * of the certificate and fetches what is published there, so testing it against anything
 * less than a real certificate and a real signed CRL would be testing the test.
 */
public final class TestCa {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final AtomicLong serials = new AtomicLong(100);
    private final KeyPair keyPair;
    private final X509Certificate certificate;
    private final X500Name name;

    public TestCa(String commonName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
            this.name = new X500Name("CN=" + commonName);

            Instant now = Instant.now();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.ONE,
                    Date.from(now.minusSeconds(86400)),
                    Date.from(now.plusSeconds(86400L * 3650)),
                    name,
                    keyPair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            builder.addExtension(
                    Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            // The subject key identifier is what a leaf's authority key identifier points
            // at, and what lets an issuer be found among several of the same name.
            builder.addExtension(
                    Extension.subjectKeyIdentifier,
                    false,
                    new JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.getPublic()));
            this.certificate = convert(builder);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a test CA", e);
        }
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** What a server holds: one certificate that both signs and is encrypted to. */
    public Issued issue(String commonName, Instant notBefore, Instant notAfter, String crlUrl, String ocspUrl) {
        return issue(commonName, notBefore, notAfter, crlUrl, ocspUrl,
                KeyUsage.digitalSignature | KeyUsage.keyEncipherment);
    }

    /** The signing half of a person's pair, which is the half a stolen key belongs to. */
    public Issued issueSigning(String commonName, Instant notBefore, Instant notAfter, String crlUrl) {
        return issue(commonName, notBefore, notAfter, crlUrl, null,
                KeyUsage.digitalSignature | KeyUsage.nonRepudiation);
    }

    /** The other half, whose private key is the one that gets escrowed. */
    public Issued issueEncryption(String commonName, Instant notBefore, Instant notAfter, String crlUrl) {
        return issue(commonName, notBefore, notAfter, crlUrl, null, KeyUsage.keyEncipherment);
    }

    /** DER for a certificate that says where to ask about itself. */
    public Issued issue(
            String commonName,
            Instant notBefore,
            Instant notAfter,
            String crlUrl,
            String ocspUrl,
            int keyUsage) {
        try {
            BigInteger serial = BigInteger.valueOf(serials.getAndIncrement());
            X500Name subject = new X500Name("CN=" + commonName);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name, serial, Date.from(notBefore), Date.from(notAfter), subject, keyPair.getPublic());
            builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, commonName)));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
            builder.addExtension(
                    Extension.authorityKeyIdentifier,
                    false,
                    new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(certificate));
            if (crlUrl != null) {
                builder.addExtension(
                        Extension.cRLDistributionPoints,
                        false,
                        new CRLDistPoint(new DistributionPoint[] {
                            new DistributionPoint(
                                    new DistributionPointName(new GeneralNames(
                                            new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl))),
                                    null,
                                    null)
                        }));
            }
            if (ocspUrl != null) {
                // Alongside a caIssuers entry, because a real one carries both and the
                // reading has to tell them apart by their access method rather than by
                // being the only URI in the extension.
                builder.addExtension(
                        Extension.authorityInfoAccess,
                        false,
                        new AuthorityInformationAccess(new AccessDescription[] {
                            new AccessDescription(
                                    new ASN1ObjectIdentifier("1.3.6.1.5.5.7.48.2"),
                                    new GeneralName(
                                            GeneralName.uniformResourceIdentifier,
                                            "http://pki.example.gov/ca.cer")),
                            new AccessDescription(
                                    AccessDescription.id_ad_ocsp,
                                    new GeneralName(GeneralName.uniformResourceIdentifier, ocspUrl))
                        }));
            }
            X509Certificate issued = convert(builder);
            return new Issued(serial, issued, issued.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Could not issue a test certificate", e);
        }
    }

    /** What was issued, and what will be needed to talk about it. */
    public record Issued(BigInteger serial, X509Certificate certificate, byte[] der) {}

    /**
     * The authority's list.
     *
     * @param revoked serial number to the moment it was revoked
     */
    public byte[] crl(Instant thisUpdate, Instant nextUpdate, Map<BigInteger, Instant> revoked) {
        try {
            JcaX509v2CRLBuilder builder = new JcaX509v2CRLBuilder(certificate, Date.from(thisUpdate));
            builder.setNextUpdate(Date.from(nextUpdate));
            for (Map.Entry<BigInteger, Instant> entry : revoked.entrySet()) {
                builder.addCRLEntry(entry.getKey(), Date.from(entry.getValue()), CRLReason.keyCompromise);
            }
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(keyPair.getPrivate());
            X509CRL crl = new JcaX509CRLConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCRL(builder.build(signer));
            return crl.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a test CRL", e);
        }
    }

    /** A CRL with nothing on it, which is what a healthy authority publishes. */
    public byte[] emptyCrl() {
        Instant now = Instant.now();
        return crl(now.minusSeconds(3600), now.plusSeconds(86400), Map.of());
    }

    /** Written out as PEM, which is how a CA hands its certificate over. */
    public String certificatePem() {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode the test CA certificate", e);
        }
    }

    private X509Certificate convert(JcaX509v3CertificateBuilder builder) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));
    }
}
