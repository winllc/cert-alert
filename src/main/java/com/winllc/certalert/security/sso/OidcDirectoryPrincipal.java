package com.winllc.certalert.security.sso;

import com.winllc.certalert.security.DirectoryPrincipal;
import java.util.Map;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * A person signed in by an OpenID Connect provider: the directory person they were matched
 * to - so everything that asks who is signed in gets the same answer it gets for a
 * certificate or a password - carrying the ID token as well, as Spring Security expects of
 * an OIDC sign-in.
 */
public final class OidcDirectoryPrincipal extends DirectoryPrincipal implements OidcUser {

    private final OidcIdToken idToken;
    private final OidcUserInfo userInfo;

    OidcDirectoryPrincipal(DirectoryPrincipal person, OidcIdToken idToken, OidcUserInfo userInfo) {
        super(person);
        this.idToken = new OidcIdToken(idToken.getTokenValue(), idToken.getIssuedAt(), idToken.getExpiresAt(),
                storable(idToken.getClaims()));
        this.userInfo = userInfo == null ? null : new OidcUserInfo(storable(userInfo.getClaims()));
    }

    /**
     * The claims as a stored session can keep them: strings, numbers, booleans, times, and
     * lists and maps of those, in JDK collections. Anything else - the issuer as a URL, as
     * Spring converts it, or a JSON library's own map type - is kept as its text: the
     * stored-session allow-list refuses those types. The token reads the issuer back as a URL
     * when asked.
     */
    private static Map<String, Object> storable(Map<String, Object> claims) {
        Map<String, Object> kept = new java.util.LinkedHashMap<>();
        claims.forEach((name, value) -> kept.put(name, storableValue(value)));
        return kept;
    }

    private static Object storableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number
                || value instanceof java.time.Instant) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), storableValue(v)));
            return copy;
        }
        if (value instanceof java.util.Collection<?> many) {
            java.util.List<Object> copy = new java.util.ArrayList<>();
            many.forEach(v -> copy.add(storableValue(v)));
            return copy;
        }
        return value.toString();
    }

    @Override
    public Map<String, Object> getClaims() {
        return idToken.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return userInfo;
    }

    @Override
    public OidcIdToken getIdToken() {
        return idToken;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return idToken.getClaims();
    }

    @Override
    public String getName() {
        return getUsername();
    }
}
