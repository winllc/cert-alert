package com.winllc.certalert.security.sso;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.security.ClientCertificateDetails;
import com.winllc.certalert.security.ClientCertificateHeaderFilter;
import com.winllc.certalert.security.ConsentBanner;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.support.TestCertificates;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.FlashMap;

/**
 * Every kind of value this application keeps in a session, written to the database and read
 * back through the real JDBC session repository - which MockMvc's in-memory sessions never
 * do - and values that are not this application's refused on the way back.
 */
@SpringBootTest(properties = "cert-alert.session.store=jdbc")
@ActiveProfiles("test")
class StoredSessionAttributesTest {

    @Autowired
    @SuppressWarnings("rawtypes")
    private FindByIndexNameSessionRepository sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM SPRING_SESSION");
    }

    private static List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));
    }

    private static DirectoryPrincipal person(DirectoryPrincipal.AuthenticationMethod method) {
        return new DirectoryPrincipal("alice@example.gov", "Alice Archer", 42L, "uid=alice,ou=people,dc=example,dc=gov",
                method, authorities());
    }

    @SuppressWarnings("unchecked")
    private Object roundTrip(String name, Object value) {
        Session session = (Session) sessions.createSession();
        session.setAttribute(name, value);
        sessions.save(session);
        Session read = (Session) sessions.findById(session.getId());
        assertThat(read).as("the session is still there").isNotNull();
        return read.getAttribute(name);
    }

    @Test
    void everyKindOfValueTheApplicationKeepsComesBack() throws Exception {
        Instant now = Instant.now();
        X509Certificate certificate = TestCertificates.certificate("alice@example.gov", now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(30)));

        // A password sign-in.
        UsernamePasswordAuthenticationToken password =
                UsernamePasswordAuthenticationToken.authenticated(person(DirectoryPrincipal.AuthenticationMethod.LDAP),
                        null, authorities());
        password.setDetails(new WebAuthenticationDetails("192.0.2.1", null));

        // A client certificate, with the chain its details carry.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ClientCertificateHeaderFilter.CERTIFICATE_ATTRIBUTE, new X509Certificate[] {certificate});
        PreAuthenticatedAuthenticationToken x509 = new PreAuthenticatedAuthenticationToken(
                person(DirectoryPrincipal.AuthenticationMethod.X509), certificate, authorities());
        x509.setDetails(new ClientCertificateDetails(request));

        // OpenID Connect, whose ID token Spring gives an issuer as a java.net.URL.
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", URI.create("https://idp.example.gov").toURL());
        claims.put("sub", "subject-1");
        claims.put("aud", List.of("cert-alert"));
        claims.put("iat", now);
        claims.put("exp", now.plusSeconds(300));
        claims.put("groups", new ArrayList<>(List.of("a", "b")));
        claims.put("address", Map.of("country", "US"));
        OidcDirectoryPrincipal oidcPerson = new OidcDirectoryPrincipal(
                person(DirectoryPrincipal.AuthenticationMethod.OIDC),
                new OidcIdToken("token", now, now.plusSeconds(300), claims), null);
        OAuth2AuthenticationToken oidc = new OAuth2AuthenticationToken(oidcPerson, authorities(), "oidc");

        // SAML.
        SamlDirectoryPrincipal samlPerson = new SamlDirectoryPrincipal(
                person(DirectoryPrincipal.AuthenticationMethod.SAML), "alice");
        Saml2Authentication saml = new Saml2Authentication(samlPerson, "<samlp:Response/>", authorities());

        // A certificate's authentication is checked per request and kept only as the session
        // policy keeps it, without the certificate.
        var storedX509 = com.winllc.certalert.security.SessionPolicy.storable(x509);
        assertThat(storedX509.getCredentials()).isNull();
        for (var authentication : List.of(password, storedX509, oidc, saml)) {
            Object back = roundTrip("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
            assertThat(back).as(authentication.getClass().getSimpleName()).isInstanceOfSatisfying(
                    SecurityContextImpl.class, context -> {
                        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(DirectoryPrincipal.class);
                        assertThat(context.getAuthentication().getAuthorities()).hasSize(3);
                    });
            // The session policy keeps who signed in, too.
            assertThat(roundTrip("com.winllc.certalert.security.SessionPolicy.signedInAs", authentication))
                    .isNotNull();
        }
        OAuth2AuthenticationToken oidcBack = (OAuth2AuthenticationToken)
                ((SecurityContextImpl) roundTrip("ctx", new SecurityContextImpl(oidc))).getAuthentication();
        assertThat(((OidcDirectoryPrincipal) oidcBack.getPrincipal()).getIdToken().getIssuer())
                .hasToString("https://idp.example.gov");

        // OIDC's pending authorization request, CSRF, the saved request, flash messages.
        OAuth2AuthorizationRequest pending = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://idp.example.gov/authorize")
                .clientId("cert-alert")
                .redirectUri("https://certalert.example.gov/login/oauth2/code/oidc")
                .state("state")
                .scopes(Set.of("openid"))
                .attributes(Map.of("registration_id", "oidc", "nonce", "n", "code_verifier", "v"))
                .additionalParameters(Map.of("nonce", "hash", "code_challenge", "c", "code_challenge_method", "S256"))
                .build();
        assertThat(roundTrip("oauth2", pending)).isInstanceOf(OAuth2AuthorizationRequest.class);
        assertThat(roundTrip("csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t")))
                .isInstanceOf(DefaultCsrfToken.class);
        MockHttpServletRequest saved = new MockHttpServletRequest("GET", "/users");
        saved.setQueryString("q=alice");
        saved.addParameter("q", "alice");
        assertThat(roundTrip("saved", new DefaultSavedRequest(saved))).isInstanceOf(DefaultSavedRequest.class);
        FlashMap flash = new FlashMap();
        flash.put("error", "That license was not installed.");
        flash.setTargetRequestPath("/admin/license");
        assertThat(roundTrip("flash", new ArrayList<>(List.of(flash)))).isInstanceOf(List.class);

        // The consent banner, and the session policy's own values.
        assertThat(roundTrip(ConsentBanner.ACKNOWLEDGED, Boolean.TRUE)).isEqualTo(Boolean.TRUE);
        assertThat(roundTrip("at", now)).isEqualTo(now);
        assertThat(roundTrip("limit", Duration.ofMinutes(15))).isEqualTo(Duration.ofMinutes(15));
        assertThat(roundTrip("ended", "concurrent")).isEqualTo("concurrent");
    }

    /** Values that are not this application's are refused on the way back, and read as absent. */
    @Test
    @SuppressWarnings("unchecked")
    void whatIsNotOnTheAllowListReadsAsAbsent() throws Exception {
        Session session = (Session) sessions.createSession();
        session.setAttribute("kept", "still here");
        sessions.save(session);
        String primaryId = jdbc.queryForObject(
                "SELECT PRIMARY_ID FROM SPRING_SESSION WHERE SESSION_ID = ?", String.class, session.getId());

        PriorityQueue<String> gadget = new PriorityQueue<>(List.of("x"));
        Map<String, Serializable> refused = Map.of(
                "queue", gadget,
                "url", URI.create("http://attacker.example/").toURL(),
                "rmi", new java.rmi.server.UID(),
                "wrapped", new ArrayList<Object>(List.of("fine", gadget)));
        for (Map.Entry<String, Serializable> entry : refused.entrySet()) {
            jdbc.update("INSERT INTO SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) "
                    + "VALUES (?, ?, ?)", primaryId, entry.getKey(), bytes(entry.getValue()));
        }

        Session read = (Session) sessions.findById(session.getId());
        assertThat(read).isNotNull();
        assertThat((Object) read.getAttribute("kept")).isEqualTo("still here");
        for (String name : refused.keySet()) {
            assertThat((Object) read.getAttribute(name)).as(name).isNull();
        }
    }

    private static byte[] bytes(Object value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = new ObjectOutputStream(out)) {
            stream.writeObject(value);
        }
        return out.toByteArray();
    }
}
