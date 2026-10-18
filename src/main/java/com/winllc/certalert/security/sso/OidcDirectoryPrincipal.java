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
        this.idToken = idToken;
        this.userInfo = userInfo;
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
