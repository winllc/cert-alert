package com.winllc.certalert.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
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
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.SessionIndex;
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
    public static final String SLO_URL = "https://idp.test.example/slo";
    /** The redirect-binding signature algorithm, for every message here. */
    public static final String SIG_ALG = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256;

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
        return writeMetadata(directory, false);
    }

    /** Its metadata, naming a SingleLogoutService (HTTP-Redirect) when {@code singleLogout}. */
    public Path writeMetadata(Path directory, boolean singleLogout) throws Exception {
        String logout = singleLogout
                ? "<md:SingleLogoutService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" Location=\""
                        + SLO_URL + "\"/>"
                : "";
        String metadata = """
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:KeyDescriptor use="signing">
                      <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                        <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
                      </ds:KeyInfo>
                    </md:KeyDescriptor>
                    %s
                    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="%s"/>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(ENTITY_ID, Base64.getEncoder().encodeToString(certificate.getEncoded()), logout, SSO_URL);
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
        /** The provider's session, in the AuthnStatement; none when null. */
        public String sessionIndex;
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
        authn.setSessionIndex(answer.sessionIndex);
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

    // --- single logout ----------------------------------------------------------------

    /**
     * A {@code LogoutRequest} from the provider - another application signed out - as the query
     * string of an HTTP-Redirect: deflated, signed over the query (SAMLRequest, RelayState,
     * SigAlg), with its key or, for a test, another.
     */
    public String logoutRequestQuery(String destination, String nameId, String sessionIndex, String id,
            boolean signedByAnotherKey) throws Exception {
        XMLObjectBuilderFactory builders = XMLObjectProviderRegistrySupport.getBuilderFactory();
        LogoutRequest request = build(builders, LogoutRequest.DEFAULT_ELEMENT_NAME);
        request.setID(id);
        request.setVersion(SAMLVersion.VERSION_20);
        request.setIssueInstant(Instant.now());
        request.setDestination(destination);
        request.setIssuer(issuer(builders, ENTITY_ID));
        NameID name = build(builders, NameID.DEFAULT_ELEMENT_NAME);
        name.setValue(nameId);
        name.setFormat(NameID.UNSPECIFIED);
        request.setNameID(name);
        if (sessionIndex != null) {
            SessionIndex index = build(builders, SessionIndex.DEFAULT_ELEMENT_NAME);
            index.setValue(sessionIndex);
            request.getSessionIndexes().add(index);
        }
        return redirectQuery("SAMLRequest", request, "idp-relay", signedByAnotherKey);
    }

    /** The provider's {@code LogoutResponse} to a request of the service provider's, as a redirect's query. */
    public String logoutResponseQuery(String destination, String inResponseTo, String relayState,
            boolean signedByAnotherKey) throws Exception {
        XMLObjectBuilderFactory builders = XMLObjectProviderRegistrySupport.getBuilderFactory();
        LogoutResponse response = build(builders, LogoutResponse.DEFAULT_ELEMENT_NAME);
        response.setID("_" + UUID.randomUUID());
        response.setVersion(SAMLVersion.VERSION_20);
        response.setIssueInstant(Instant.now());
        response.setDestination(destination);
        response.setInResponseTo(inResponseTo);
        response.setIssuer(issuer(builders, ENTITY_ID));
        Status status = build(builders, Status.DEFAULT_ELEMENT_NAME);
        StatusCode code = build(builders, StatusCode.DEFAULT_ELEMENT_NAME);
        code.setValue(StatusCode.SUCCESS);
        status.setStatusCode(code);
        response.setStatus(status);
        return redirectQuery("SAMLResponse", response, relayState, signedByAnotherKey);
    }

    private String redirectQuery(String parameter, org.opensaml.core.xml.XMLObject message, String relayState,
            boolean signedByAnotherKey) throws Exception {
        Element element = XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(message).marshall(message);
        byte[] xml = SerializeSupport.nodeToString(element).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(deflated, new Deflater(Deflater.DEFLATED, true))) {
            out.write(xml);
        }
        StringBuilder query = new StringBuilder(parameter).append('=')
                .append(URLEncoder.encode(Base64.getEncoder().encodeToString(deflated.toByteArray()), StandardCharsets.UTF_8));
        if (relayState != null) {
            query.append("&RelayState=").append(URLEncoder.encode(relayState, StandardCharsets.UTF_8));
        }
        query.append("&SigAlg=").append(URLEncoder.encode(SIG_ALG, StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(signedByAnotherKey ? otherKeys.getPrivate() : keys.getPrivate());
        signer.update(query.toString().getBytes(StandardCharsets.UTF_8));
        return query.append("&Signature=")
                .append(URLEncoder.encode(Base64.getEncoder().encodeToString(signer.sign()), StandardCharsets.UTF_8))
                .toString();
    }

    /** A redirect's query parameters, decoded. */
    public static Map<String, String> queryOf(String url) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int at = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    /** The XML a redirect carries in {@code parameter} (SAMLRequest or SAMLResponse). */
    public static String inflated(String url, String parameter) throws Exception {
        byte[] deflated = Base64.getDecoder().decode(queryOf(url).get(parameter));
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(deflated), new Inflater(true))) {
            in.transferTo(xml);
        }
        return xml.toString(StandardCharsets.UTF_8);
    }

    /**
     * Whether a redirect's message is signed - over its raw query, as the binding says - by
     * {@code certificate}'s key with RSA-SHA256.
     */
    public static boolean signedBy(String url, X509Certificate certificate) throws Exception {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int at = pair.indexOf('=');
            raw.put(pair.substring(0, at), pair.substring(at + 1));
        }
        if (!SIG_ALG.equals(URLDecoder.decode(raw.getOrDefault("SigAlg", ""), StandardCharsets.UTF_8))
                || raw.get("Signature") == null) {
            return false;
        }
        StringBuilder content = new StringBuilder();
        for (String name : List.of("SAMLRequest", "SAMLResponse", "RelayState", "SigAlg")) {
            if (raw.containsKey(name)) {
                content.append(content.isEmpty() ? "" : "&").append(name).append('=').append(raw.get(name));
            }
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(content.toString().getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(URLDecoder.decode(raw.get("Signature"), StandardCharsets.UTF_8)));
    }

    /**
     * A service provider's signing key and certificate, made fresh, written as PEM files
     * ({@code sp-signing.key}, PKCS#8, and {@code sp-signing.crt}) into {@code directory} for
     * {@code signing-key-location} and {@code signing-certificate-location}.
     */
    public static X509Certificate writeServiceProviderKey(Path directory) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = selfSigned(pair, "CN=CertAlert test SP");
        Base64.Encoder pem = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        Files.writeString(directory.resolve("sp-signing.key"), "-----BEGIN PRIVATE KEY-----\n"
                + pem.encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        Files.writeString(directory.resolve("sp-signing.crt"), "-----BEGIN CERTIFICATE-----\n"
                + pem.encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n");
        return certificate;
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
