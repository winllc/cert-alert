package com.winllc.certalert.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Roles from groups, bound from {@code cert-alert.security.groups}: a directory group (its
 * DN, from the person's {@code memberOf}) or a group an identity provider says a person is
 * in (whatever its groups claim or attribute carries - a name, a DN, an object id).
 *
 * <p>Nothing is granted by a group unless it is listed here. In particular nobody becomes
 * an administrator because an identity provider says they are in a group called "admins":
 * the group has to be named in {@code admin}.
 */
public class RoleProperties {

    /** Groups whose members are administrators. */
    private List<String> admin = new ArrayList<>();

    /**
     * Groups whose members are auditors: they can read everything an administrator can -
     * the administration page, the audit log, the license - and change nothing at all.
     */
    private List<String> auditor = new ArrayList<>();

    /**
     * Whether a person's directory groups count, read from {@link #memberOfAttribute} on
     * their entry when they sign in, however they sign in. Off reads only what an identity
     * provider says.
     */
    private boolean directory = true;

    /** The attribute on a person's entry that lists the groups they are in. */
    private String memberOfAttribute = "memberOf";

    /**
     * How long a person's directory groups are remembered. A client certificate is checked
     * on every request, and this keeps that from being a directory read every time; a
     * change of group takes up to this long to count.
     */
    private Duration cacheFor = Duration.ofMinutes(5);

    /** Whether any group grants anything - and so whether groups are worth reading at all. */
    public boolean anyMapped() {
        return admin.stream().anyMatch(value -> value != null && !value.isBlank())
                || auditor.stream().anyMatch(value -> value != null && !value.isBlank());
    }

    public List<String> getAdmin() {
        return admin;
    }

    public void setAdmin(List<String> admin) {
        this.admin = admin;
    }

    public List<String> getAuditor() {
        return auditor;
    }

    public void setAuditor(List<String> auditor) {
        this.auditor = auditor;
    }

    public boolean isDirectory() {
        return directory;
    }

    public void setDirectory(boolean directory) {
        this.directory = directory;
    }

    public String getMemberOfAttribute() {
        return memberOfAttribute;
    }

    public void setMemberOfAttribute(String memberOfAttribute) {
        this.memberOfAttribute = memberOfAttribute;
    }

    public Duration getCacheFor() {
        return cacheFor;
    }

    public void setCacheFor(Duration cacheFor) {
        this.cacheFor = cacheFor;
    }
}
