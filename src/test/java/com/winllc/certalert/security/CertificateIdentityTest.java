package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.support.TestCertificates;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

/** The values a CAC or PIV certificate offers for finding its holder: UPN, EDIPI, address, UUID. */
class CertificateIdentityTest {

    private static final String UPN_OID = "1.3.6.1.4.1.311.20.2.3";

    @Test
    void aCacCarriesItsUpnEdipiAddressAndUuid() throws Exception {
        X509Certificate cac = certificate("CN=DOE.JANE.Q.1234567890, OU=USN, OU=PKI, OU=DoD, O=U.S. Government, C=US",
                upn("1234567890@mil"),
                new GeneralName(GeneralName.rfc822Name, "jane.q.doe.civ@mail.mil"),
                new GeneralName(GeneralName.uniformResourceIdentifier, "urn:uuid:a1b2c3d4-0000-1111-2222-333344445555"));

        CertificateIdentity identity = CertificateIdentity.of(cac);

        assertThat(identity.upn()).hasValue("1234567890@mil");
        assertThat(identity.edipi()).hasValue("1234567890");
        assertThat(identity.email()).hasValue("jane.q.doe.civ@mail.mil");
        assertThat(identity.uuid()).hasValue("a1b2c3d4-0000-1111-2222-333344445555");
    }

    @Test
    void withoutAMilUpnTheEdipiComesFromTheCommonName() throws Exception {
        X509Certificate certificate = certificate("CN=DOE.JOHN.R.2345678901, O=U.S. Government, C=US",
                upn("john.doe@agency.example.gov"));

        CertificateIdentity identity = CertificateIdentity.of(certificate);

        assertThat(identity.upn()).hasValue("john.doe@agency.example.gov");
        assertThat(identity.edipi()).hasValue("2345678901");
    }

    @Test
    void withoutAnAlternativeNameTheAddressComesFromTheSubject() throws Exception {
        X509Certificate certificate = certificate("EMAILADDRESS=ops@example.gov, CN=Operations, O=Example, C=US");

        CertificateIdentity identity = CertificateIdentity.of(certificate);

        assertThat(identity.email()).hasValue("ops@example.gov");
        assertThat(identity.upn()).isEmpty();
        assertThat(identity.edipi()).isEmpty();
        assertThat(identity.uuid()).isEmpty();
    }

    @Test
    void valuesAreEscapedWhereTheyAreFilledIn() throws Exception {
        // An address chosen to widen the search it is put into.
        X509Certificate certificate = certificate("CN=Mallory, O=Example, C=US",
                new GeneralName(GeneralName.rfc822Name, "*)(uid=*"));

        assertThat(CertificateIdentity.of(certificate).fill("(&(objectClass=person)(mail={email}))"))
                .hasValue("(&(objectClass=person)(mail=\\2a\\29\\28uid=\\2a))");
    }

    @Test
    void aFilterNamingAValueTheCertificateLacksIsNotUsed() throws Exception {
        X509Certificate certificate = certificate("CN=Plain, O=Example, C=US",
                new GeneralName(GeneralName.rfc822Name, "plain@example.gov"));
        CertificateIdentity identity = CertificateIdentity.of(certificate);

        // With {edipi} empty, "(uid=)" would match whatever has an empty uid - so nothing.
        assertThat(identity.fill("(|(uid={edipi})(mail={email}))")).isEmpty();
        assertThat(identity.missing("(|(uid={edipi})(mail={email}))")).containsExactly("{edipi}");
        assertThat(identity.fill("(mail={email})")).hasValue("(mail=plain@example.gov)");
    }

    private static GeneralName upn(String value) {
        ASN1Encodable[] otherName = {
            new ASN1ObjectIdentifier(UPN_OID), new DERTaggedObject(true, 0, new DERUTF8String(value))
        };
        return new GeneralName(GeneralName.otherName, new DERSequence(otherName));
    }

    static X509Certificate certificate(String subject, GeneralName... alternativeNames) throws Exception {
        KeyPair keys = TestCertificates.sharedKeyPair();
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=Test Issuer"),
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(30))),
                new X500Name(subject),
                keys.getPublic());
        if (alternativeNames.length > 0) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new ArrayList<>(List.of(alternativeNames)).toArray(GeneralName[]::new)));
        }
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }
}
