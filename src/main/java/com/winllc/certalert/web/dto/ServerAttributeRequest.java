package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.ServerAttributeType;
import java.util.List;

/**
 * Defining a managed attribute, or changing one.
 *
 * <p>Nothing is validated here beyond what the binder does: what a name, a kind and a list
 * of options may be is the service's to say, so the same rule holds however the request
 * arrives and the refusal says what the rule is.
 *
 * @param ldapAttribute the attribute on the directory entry that this manages
 * @param name what to call it on the page; left out means the attribute's own name
 * @param multiValued whether a server may hold several; left out means one
 * @param options what a drop-down may be set to; ignored for the other kinds
 * @param displayOrder where it sits among the others, or null to leave it where it is
 */
public record ServerAttributeRequest(
        String ldapAttribute,
        String name,
        String description,
        ServerAttributeType type,
        Boolean multiValued,
        List<String> options,
        Integer displayOrder) {

    /** A request that leaves it out is asking for one value, not for a failure. */
    public boolean holdsSeveral() {
        return Boolean.TRUE.equals(multiValued);
    }
}
