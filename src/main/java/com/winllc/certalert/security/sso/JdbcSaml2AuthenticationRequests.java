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
        jdbc.update("DELETE FROM saml_authn_request WHERE created_at < ?", Timestamp.from(now.minus(VALID_FOR)));
        jdbc.update(
                "INSERT INTO saml_authn_request (relay_state, request, consented, created_at) VALUES (?, ?, ?, ?)",
                relayState, serialize(authenticationRequest), ConsentFilter.acknowledged(request), Timestamp.from(now));
    }

    /** Already taken when it was loaded; anything still there goes now. */
    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        return take(request);
    }

    private AbstractSaml2AuthenticationRequest take(HttpServletRequest request) {
        String relayState = request.getParameter(RELAY_STATE);
        Row row = find(relayState);
        if (row == null) {
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

    private record Row(AbstractSaml2AuthenticationRequest request, boolean consented) {}

    private Row find(String relayState) {
        if (relayState == null || relayState.isBlank() || relayState.length() > 128) {
            return null;
        }
        Instant oldest = Instant.now(clock).minus(VALID_FOR);
        List<Row> rows = jdbc.query(
                "SELECT request, consented FROM saml_authn_request WHERE relay_state = ? AND created_at >= ?",
                (rs, i) -> {
                    AbstractSaml2AuthenticationRequest read = deserialize(rs.getBytes(1));
                    return read == null ? null : new Row(read, rs.getBoolean(2));
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
