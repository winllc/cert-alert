package com.winllc.certalert.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * An OpenID Connect provider for the tests, on loopback: discovery, a key set, and a token
 * endpoint that answers with whatever ID token the test asks for - signed with the
 * provider's own key unless the test asks for another.
 *
 * <p>The keys are made fresh every run; nothing is kept.
 */
public final class TestOidcProvider implements AutoCloseable {

    public static final String CLIENT_ID = "cert-alert-test";

    private final HttpServer server;
    private final RSAKey key;
    private final RSAKey otherKey;

    /** What the next token request answers with, built from the claims a good answer would have. */
    private volatile UnaryOperator<JWTClaimsSet.Builder> claims = UnaryOperator.identity();
    private volatile boolean signWithOtherKey;
    private volatile String nonce;
    private volatile String codeChallenge;
    private volatile String lastCodeVerifier;
    private volatile Map<String, Object> extraClaims = Map.of();

    public TestOidcProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the test OpenID provider", e);
        }
        server.createContext("/.well-known/openid-configuration", exchange -> json(exchange, """
                {"issuer":"%1$s","authorization_endpoint":"%1$s/authorize","token_endpoint":"%1$s/token",
                 "jwks_uri":"%1$s/jwks","response_types_supported":["code"],"subject_types_supported":["public"],
                 "id_token_signing_alg_values_supported":["RS256"],"code_challenge_methods_supported":["S256"]}
                """.formatted(issuer())));
        server.createContext("/jwks", exchange -> json(exchange, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/token", this::token);
        server.start();
    }

    public String issuer() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** What the authorization request said, as the browser would carry it to the provider. */
    public void authorizationRequested(String nonce, String codeChallenge) {
        this.nonce = nonce;
        this.codeChallenge = codeChallenge;
    }

    /** The next ID token, changed from a good one by this. */
    public void nextToken(UnaryOperator<JWTClaimsSet.Builder> change, Map<String, Object> extra, boolean otherKey) {
        this.claims = change;
        this.extraClaims = extra;
        this.signWithOtherKey = otherKey;
    }

    public String lastCodeVerifier() {
        return lastCodeVerifier;
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            int at = pair.indexOf('=');
            if (at > 0) {
                form.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
            }
        }
        lastCodeVerifier = form.get("code_verifier");
        // PKCE, as a provider checks it: the verifier must hash to the challenge.
        if (lastCodeVerifier == null || !s256(lastCodeVerifier).equals(codeChallenge)) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet.Builder good = new JWTClaimsSet.Builder()
                    .issuer(issuer())
                    .audience(CLIENT_ID)
                    .subject("subject-1")
                    .issueTime(new Date(now))
                    .expirationTime(new Date(now + 300_000))
                    .claim("nonce", nonce);
            extraClaims.forEach(good::claim);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").type(JOSEObjectType.JWT).build(),
                    claims.apply(good).build());
            jwt.sign(new RSASSASigner(signWithOtherKey ? otherKey : key));
            json(exchange, """
                    {"access_token":"at","token_type":"Bearer","expires_in":300,"scope":"openid","id_token":"%s"}
                    """.formatted(jwt.serialize()));
        } catch (Exception e) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        }
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
