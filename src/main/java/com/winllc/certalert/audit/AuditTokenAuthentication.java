package com.winllc.certalert.audit;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A request carrying a valid audit API token: a service, allowed to read the audit trail. */
public class AuditTokenAuthentication extends AbstractAuthenticationToken {

    public static final String AUTHORITY = "AUDIT_READ";

    private final Long tokenId;
    private final String tokenName;

    public AuditTokenAuthentication(Long tokenId, String tokenName) {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        this.tokenId = tokenId;
        this.tokenName = tokenName;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return "audit-token:" + tokenId;
    }

    @Override
    public String getName() {
        return "audit-token:" + tokenId;
    }

    public String tokenName() {
        return tokenName;
    }
}
