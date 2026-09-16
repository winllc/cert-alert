package com.winllc.certalert.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed-in person.
 *
 * <p>Carries the directory entry they were resolved to, where there is one, so the UI can
 * say who is signed in and a later feature can scope a view to their own certificates.
 * There is never a password here: one path authenticates with a certificate, the other by
 * binding to the directory, and neither leaves us holding credentials.
 */
public class DirectoryPrincipal implements UserDetails {

    private final String username;
    private final String displayName;
    private final Long directoryUserId;
    private final String directoryDn;
    private final AuthenticationMethod method;
    private final List<GrantedAuthority> authorities;

    public DirectoryPrincipal(
            String username,
            String displayName,
            Long directoryUserId,
            String directoryDn,
            AuthenticationMethod method,
            Collection<? extends GrantedAuthority> authorities) {
        this.username = username;
        this.displayName = displayName;
        this.directoryUserId = directoryUserId;
        this.directoryDn = directoryDn;
        this.method = method;
        this.authorities = List.copyOf(authorities);
    }

    /** How this person proved who they are. */
    public enum AuthenticationMethod {
        /** A client certificate the directory publishes. */
        X509,
        /** A successful bind to the directory. */
        LDAP
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName == null ? username : displayName;
    }

    /** The directory entry this person was resolved to, or null if they are not in it. */
    public Long getDirectoryUserId() {
        return directoryUserId;
    }

    public String getDirectoryDn() {
        return directoryDn;
    }

    public AuthenticationMethod getMethod() {
        return method;
    }
}
