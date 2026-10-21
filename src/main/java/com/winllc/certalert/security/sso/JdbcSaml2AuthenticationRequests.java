package com.winllc.certalert.security.sso;

import com.winllc.certalert.security.ConsentFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

/**
 * The SAML authentication requests waiting for an answer, in the database
 * ({@code saml_authn_request}, V24) by their RelayState, rather than in the session.
 *
 * <p>The identity provider's answer is a cross-site POST, which the SameSite=Lax session
 * cookie does not come with; and with more than one replica it may reach another replica.
 * Keyed by RelayState, the request is found either way. Each is read once - removed as the
 * answer is read - and only for {@link #VALID_FOR}; together with requiring every
 * response to say which request it answers, that rules out replaying one.
 *
 * <p>Bound to the browser that started the sign-in: it is given a random nonce in a cookie of
 * its own ({@value #COOKIE} - HttpOnly, Secure, SameSite=None so it comes back with the
 * provider's cross-site POST, and only to the assertion consumer), and the row keeps its
 * hash. An answer from a browser without it is not accepted, so a response somebody obtained
 * for their own account cannot be posted from a victim's browser to sign it in (login CSRF).
 *
 * <p>Also remembers whether the browser that started the sign-in had agreed to the
 * notice-and-consent banner, so the session the answer creates carries that agreement
 * (see {@link SsoSignIn}).
 */
public class JdbcSaml2AuthenticationRequests implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {

    private static final Logger log = LoggerFactory.getLogger(JdbcSaml2AuthenticationRequests.class);

    /** How long a request waits for its answer. */
    static final Duration VALID_FOR = Duration.ofMinutes(10);

    /** Set on the request whose answer was read, when the browser had agreed to the banner. */
    public static final String CONSENTED = JdbcSaml2AuthenticationRequests.class.getName() + ".consented";

    private static final String RELAY_STATE = "RelayState";

    /** The cookie binding a sign-in to the browser that started it. */
    public static final String COOKIE = "CERTALERT_SAML_BINDING";

    /** Where the cookie is sent: the assertion consumer only. */
    private static final String COOKIE_PATH = "/login/saml2/sso";

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** Only what an authentication request is made of may be read back. */
    private static final ObjectInputFilter ALLOWED = ObjectInputFilter.Config.createFilter(
            "org.springframework.security.saml2.**;java.lang.*;java.util.*;java.time.*;maxdepth=5;!*");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcSaml2AuthenticationRequests(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    /**
     * The request this answer is to - taken, not just read: it is deleted here, and only the
     * caller whose delete removed it gets it. Spring Security reads it once per answer, and
     * removing it afterwards as well would leave a moment in which two copies of one answer
     * both found it.
     */
    @Override
    public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
        return take(request);
    }

    @Override
    public void saveAuthenticationRequest(
            AbstractSaml2AuthenticationRequest authenticationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (authenticationRequest == null) {
            removeAuthenticationRequest(request, response);
            return;
        }
        String relayState = authenticationRequest.getRelayState();
        if (relayState == null || relayState.isBlank() || relayState.length() > 128) {
            throw new IllegalStateException("A SAML authentication request needs a RelayState to be found by");
        }
        Instant now = Instant.now(clock);
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        String binding = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        jdbc.update("DELETE FROM saml_authn_request WHERE created_at < ?", Timestamp.from(now.minus(VALID_FOR)));
        jdbc.update(
                "INSERT INTO saml_authn_request (relay_state, request, binding, consented, created_at) VALUES (?, ?, ?, ?, ?)",
                relayState, serialize(authenticationRequest), sha256(binding), ConsentFilter.acknowledged(request),
                Timestamp.from(now));
        response.addHeader("Set-Cookie", org.springframework.http.ResponseCookie.from(COOKIE, binding)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path(COOKIE_PATH)
                .maxAge(VALID_FOR)
                .build()
                .toString());
    }

    /** Already taken when it was loaded; anything still there goes now, and so does the cookie. */
    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        AbstractSaml2AuthenticationRequest taken = take(request);
        if (response != null) {
            response.addHeader("Set-Cookie", org.springframework.http.ResponseCookie.from(COOKIE, "")
                    .httpOnly(true).secure(true).sameSite("None").path(COOKIE_PATH).maxAge(0).build().toString());
        }
        return taken;
    }

    private AbstractSaml2AuthenticationRequest take(HttpServletRequest request) {
        String relayState = request.getParameter(RELAY_STATE);
        Row row = find(relayState);
        if (row == null) {
            return null;
        }
        String presented = cookie(request);
        if (presented == null || !java.security.MessageDigest.isEqual(
                sha256(presented).getBytes(StandardCharsets.US_ASCII), row.binding().getBytes(StandardCharsets.US_ASCII))) {
            // Not the browser that asked. Left in place, for the one that did.
            log.warn("A SAML answer came from a browser that did not start that sign-in; not accepted");
            return null;
        }
        // Whoever deletes it is the one answer it gets; a replay arriving alongside finds nothing.
        if (jdbc.update("DELETE FROM saml_authn_request WHERE relay_state = ?", relayState) == 0) {
            return null;
        }
        if (row.consented()) {
            request.setAttribute(CONSENTED, Boolean.TRUE);
        }
        return row.request();
    }

    private record Row(AbstractSaml2AuthenticationRequest request, String binding, boolean consented) {}

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Row find(String relayState) {
        if (relayState == null || relayState.isBlank() || relayState.length() > 128) {
            return null;
        }
        Instant oldest = Instant.now(clock).minus(VALID_FOR);
        List<Row> rows = jdbc.query(
                "SELECT request, binding, consented FROM saml_authn_request WHERE relay_state = ? AND created_at >= ?",
                (rs, i) -> {
                    AbstractSaml2AuthenticationRequest read = deserialize(rs.getBytes(1));
                    return read == null ? null : new Row(read, rs.getString(2), rs.getBoolean(3));
                },
                relayState,
                Timestamp.from(oldest));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static byte[] serialize(AbstractSaml2AuthenticationRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(request);
        } catch (IOException e) {
            throw new IllegalStateException("Could not keep the SAML authentication request", e);
        }
        return bytes.toByteArray();
    }

    private static AbstractSaml2AuthenticationRequest deserialize(byte[] bytes) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(ALLOWED);
            return in.readObject() instanceof AbstractSaml2AuthenticationRequest request ? request : null;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            log.warn("Could not read back a SAML authentication request: {}", e.toString());
            return null;
        }
    }
}
