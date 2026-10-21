package com.winllc.certalert.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.xml.namespace.QName;
import net.shibboleth.shared.xml.SerializeSupport;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.SignatureSigningParameters;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.SignatureSupport;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.w3c.dom.Element;

/**
 * A SAML identity provider for the tests: its metadata, and signed responses built to
 * order - right, or wrong in one particular way. Keys are made fresh every run.
 */
public final class TestSamlIdentityProvider {

    public static final String ENTITY_ID = "https://idp.test.example/saml";
    public static final String SSO_URL = "https://idp.test.example/sso";

    static {
        OpenSamlInitializationService.initialize();
    }

    private final KeyPair keys;
    private final X509Certificate certificate;
    private final KeyPair otherKeys;
    private final X509Certificate otherCertificate;

    public TestSamlIdentityProvider() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
            certificate = selfSigned(keys, "CN=Test IdP");
            otherKeys = generator.generateKeyPair();
            otherCertificate = selfSigned(otherKeys, "CN=Somebody Else");
        } catch (Exception e) {
            throw new IllegalStateException("Could not make the test identity provider's keys", e);
        }
    }

    /** Its metadata, written to a file of its own, for {@code metadata-location}. */
    public Path writeMetadata(Path directory) throws Exception {
        String metadata = """
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:KeyDescriptor use="signing">
                      <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                        <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
                      </ds:KeyInfo>
                    </md:KeyDescriptor>
                    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="%s"/>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(ENTITY_ID, Base64.getEncoder().encodeToString(certificate.getEncoded()), SSO_URL);
        Path file = directory.resolve("idp-metadata.xml");
        Files.writeString(file, metadata);
        return file;
    }

    /** The ID of the authentication request carried in a redirect to the provider. */
    public static String requestId(String samlRequest) throws Exception {
        byte[] deflated = Base64.getDecoder().decode(samlRequest);
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(deflated), new Inflater(true))) {
            in.transferTo(xml);
        }
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\\sID=\"([^\"]+)\"")
                .matcher(xml.toString(StandardCharsets.UTF_8));
        if (!id.find()) {
            throw new IllegalStateException("No ID in the authentication request");
        }
        return id.group(1);
    }

    /** What a response says, each part of which a test may get wrong on purpose. */
    public static final class Answer {
        public String issuer = ENTITY_ID;
        public String inResponseTo;
        public String destination;
        public String audience;
        public String nameId;
        public Map<String, List<String>> attributes = Map.of();
        public Instant notOnOrAfter = Instant.now().plus(Duration.ofMinutes(5));
        public boolean signed = true;
        public boolean signedByAnotherKey;
    }

    /** The response, base64 as the browser posts it. */
    public String respond(Answer answer) throws Exception {
        XMLObjectBuilderFactory builders = XMLObjectProviderRegistrySupport.getBuilderFactory();
        Instant now = Instant.now();

        Response response = build(builders, Response.DEFAULT_ELEMENT_NAME);
        response.setID("_" + UUID.randomUUID());
        response.setIssueInstant(now);
        response.setVersion(SAMLVersion.VERSION_20);
        response.setDestination(answer.destination);
        response.setInResponseTo(answer.inResponseTo);
        response.setIssuer(issuer(builders, answer.issuer));
        Status status = build(builders, Status.DEFAULT_ELEMENT_NAME);
        StatusCode code = build(builders, StatusCode.DEFAULT_ELEMENT_NAME);
        code.setValue(StatusCode.SUCCESS);
        status.setStatusCode(code);
        response.setStatus(status);

        Assertion assertion = build(builders, Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setID("_" + UUID.randomUUID());
        assertion.setIssueInstant(now);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssuer(issuer(builders, answer.issuer));

        Subject subject = build(builders, Subject.DEFAULT_ELEMENT_NAME);
        NameID nameId = build(builders, NameID.DEFAULT_ELEMENT_NAME);
        nameId.setValue(answer.nameId);
        nameId.setFormat(NameID.UNSPECIFIED);
        subject.setNameID(nameId);
        SubjectConfirmation confirmation = build(builders, SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        SubjectConfirmationData data = build(builders, SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
        data.setRecipient(answer.destination);
        data.setNotOnOrAfter(answer.notOnOrAfter);
        data.setInResponseTo(answer.inResponseTo);
        confirmation.setSubjectConfirmationData(data);
        subject.getSubjectConfirmations().add(confirmation);
        assertion.setSubject(subject);

        Conditions conditions = build(builders, Conditions.DEFAULT_ELEMENT_NAME);
        conditions.setNotBefore(now.minus(Duration.ofMinutes(1)));
        conditions.setNotOnOrAfter(answer.notOnOrAfter);
        AudienceRestriction restriction = build(builders, AudienceRestriction.DEFAULT_ELEMENT_NAME);
        Audience audience = build(builders, Audience.DEFAULT_ELEMENT_NAME);
        audience.setURI(answer.audience);
        restriction.getAudiences().add(audience);
        conditions.getAudienceRestrictions().add(restriction);
        assertion.setConditions(conditions);

        AuthnStatement authn = build(builders, AuthnStatement.DEFAULT_ELEMENT_NAME);
        authn.setAuthnInstant(now);
        AuthnContext context = build(builders, AuthnContext.DEFAULT_ELEMENT_NAME);
        AuthnContextClassRef classRef = build(builders, AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
        classRef.setURI(AuthnContext.PPT_AUTHN_CTX);
        context.setAuthnContextClassRef(classRef);
        authn.setAuthnContext(context);
        assertion.getAuthnStatements().add(authn);

        if (!answer.attributes.isEmpty()) {
            AttributeStatement statement = build(builders, AttributeStatement.DEFAULT_ELEMENT_NAME);
            answer.attributes.forEach((name, values) -> {
                Attribute attribute = build(builders, Attribute.DEFAULT_ELEMENT_NAME);
                attribute.setName(name);
                for (String value : values) {
                    XSString text = (XSString) builders.getBuilder(XSString.TYPE_NAME)
                            .buildObject(org.opensaml.saml.saml2.core.AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME);
                    text.setValue(value);
                    attribute.getAttributeValues().add(text);
                }
                statement.getAttributes().add(attribute);
            });
            assertion.getAttributeStatements().add(statement);
        }

        response.getAssertions().add(assertion);
        if (answer.signed) {
            SignatureSigningParameters parameters = new SignatureSigningParameters();
            parameters.setSigningCredential(answer.signedByAnotherKey
                    ? new BasicX509Credential(otherCertificate, otherKeys.getPrivate())
                    : new BasicX509Credential(certificate, keys.getPrivate()));
            parameters.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
            parameters.setSignatureReferenceDigestMethod(SignatureConstants.ALGO_ID_DIGEST_SHA256);
            parameters.setSignatureCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
            SignatureSupport.signObject(assertion, parameters);
        }
        Element element = XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(response).marshall(response);
        return Base64.getEncoder().encodeToString(SerializeSupport.nodeToString(element).getBytes(StandardCharsets.UTF_8));
    }

    /** The same response with one value changed after it was signed. */
    public static String tampered(String response, String from, String to) {
        String xml = new String(Base64.getDecoder().decode(response), StandardCharsets.UTF_8);
        if (!xml.contains(from)) {
            throw new IllegalStateException(from + " is not in the response");
        }
        return Base64.getEncoder().encodeToString(xml.replace(from, to).getBytes(StandardCharsets.UTF_8));
    }

    private static Issuer issuer(XMLObjectBuilderFactory builders, String value) {
        Issuer issuer = build(builders, Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(value);
        return issuer;
    }

    @SuppressWarnings("unchecked")
    private static <T> T build(XMLObjectBuilderFactory builders, QName name) {
        return (T) builders.getBuilder(name).buildObject(name);
    }

    private static X509Certificate selfSigned(KeyPair keys, String subject) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name(subject);
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))), name, keys.getPublic());
        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }
}
