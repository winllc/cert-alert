package com.winllc.certalert.security.sso;

import com.winllc.certalert.security.DirectoryPrincipal;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * A person signed in by a SAML identity provider: the directory person they were matched
 * to, so everything that asks who is signed in gets the same answer it gets for a
 * certificate or a password.
 */
public final class SamlDirectoryPrincipal extends DirectoryPrincipal implements AuthenticatedPrincipal {

    /** The NameID the provider sent, kept for the logs. */
    private final String nameId;

    SamlDirectoryPrincipal(DirectoryPrincipal person, String nameId) {
        super(person);
        this.nameId = nameId;
    }

    public String getNameId() {
        return nameId;
    }

    @Override
    public String getName() {
        return getUsername();
    }
}
