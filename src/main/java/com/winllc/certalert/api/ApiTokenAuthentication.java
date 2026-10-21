package com.winllc.certalert.api;

import com.winllc.certalert.security.DirectoryPrincipalResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * A request carrying a valid API token: a service acting for the administrator who made the
 * token, with the token's scopes as far as that person's roles still allow.
 *
 * <p>Authorities: {@code SCOPE_<scope>} for each scope in force, which is what the API's
 * filter chain authorises on (see {@link ApiSecurityConfig}); and the role those scopes need,
 * so a controller that asks whether the caller is an administrator gets the answer the
 * token's scopes give. Never stored anywhere: the API's chain keeps no session.
 */
public class ApiTokenAuthentication extends AbstractAuthenticationToken {

    public static final String ROLE_API_CLIENT = "ROLE_API_CLIENT";

    private final Long tokenId;
    private final String tokenName;
    private final String createdBy;
    private final Set<ApiScope> scopes;

    public ApiTokenAuthentication(Long tokenId, String tokenName, String createdBy, Set<ApiScope> scopes) {
        super(authoritiesFor(scopes));
        this.tokenId = tokenId;
        this.tokenName = tokenName;
        this.createdBy = createdBy;
        this.scopes = Collections.unmodifiableSet(scopes);
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authoritiesFor(Set<ApiScope> scopes) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_API_CLIENT));
        authorities.add(new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_USER));
        boolean admin = scopes.stream().anyMatch(scope -> scope.requires() == ApiScope.Role.ADMIN);
        boolean auditor = scopes.stream().anyMatch(scope -> scope.requires() == ApiScope.Role.AUDITOR);
        if (admin) {
            authorities.add(new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_ADMIN));
        } else if (auditor) {
            authorities.add(new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_AUDITOR));
        }
        scopes.forEach(scope -> authorities.add(new SimpleGrantedAuthority(scope.authority())));
        return authorities;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return getName();
    }

    @Override
    public String getName() {
        return "api-token:" + tokenId;
    }

    public Long tokenId() {
        return tokenId;
    }

    public String tokenName() {
        return tokenName;
    }

    /** Who made the token, as the audit trail names them. */
    public String createdBy() {
        return createdBy;
    }

    /** The scopes in force for this request, implications included. */
    public Set<ApiScope> scopes() {
        return scopes;
    }
}
