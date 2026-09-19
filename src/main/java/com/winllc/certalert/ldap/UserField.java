package com.winllc.certalert.ldap;

import java.util.function.Function;

/**
 * The person attributes this application reads, each paired with the property that holds
 * its directory attribute name. Going through the enum means a field can never be
 * requested from the directory under one name and read back under another.
 */
public enum UserField {
    UID(LdapProperties.User::getUid),
    COMMON_NAME(LdapProperties.User::getCommonName),
    DISPLAY_NAME(LdapProperties.User::getDisplayName),
    PREFERRED_NAME(LdapProperties.User::getPreferredName),
    GIVEN_NAME(LdapProperties.User::getGivenName),
    SURNAME(LdapProperties.User::getSurname),
    MAIL(LdapProperties.User::getMail),
    IC_EMAIL(LdapProperties.User::getIcEmail),
    INTERNET_EMAIL(LdapProperties.User::getInternetEmail),
    NIPRNET_EMAIL(LdapProperties.User::getNiprnetEmail),
    SIPRNET_EMAIL(LdapProperties.User::getSiprnetEmail),
    TELEPHONE_NUMBER(LdapProperties.User::getTelephoneNumber),
    TITLE(LdapProperties.User::getTitle),
    EMPLOYEE_TYPE(LdapProperties.User::getEmployeeType),
    RANK(LdapProperties.User::getRank),
    COUNTRY_OF_AFFILIATION(LdapProperties.User::getCountryOfAffiliation),
    DUTY_ORGANIZATION(LdapProperties.User::getDutyOrganization),
    DUTY_SUB_ORGANIZATION(LdapProperties.User::getDutySubOrganization),
    ADMIN_ORGANIZATION(LdapProperties.User::getAdminOrganization),
    IS_IC_MEMBER(LdapProperties.User::getIcMember),
    IC_NETWORKS(LdapProperties.User::getIcNetworks),
    RESOURCE_SECURITY_MARK(LdapProperties.User::getResourceSecurityMark),
    ORGANIZATION(LdapProperties.User::getOrganization),
    ORGANIZATIONAL_UNIT(LdapProperties.User::getOrganizationalUnit);

    private final Function<LdapProperties.User, String> attributeName;

    UserField(Function<LdapProperties.User, String> attributeName) {
        this.attributeName = attributeName;
    }

    /** The directory attribute this field is currently mapped to. */
    public String attributeName(LdapProperties.User mapping) {
        return attributeName.apply(mapping);
    }

    /** Looks a field up by the property name used in {@code email-precedence}. */
    public static UserField byPropertyName(String name) {
        for (UserField field : values()) {
            if (field.name().replace("_", "").equalsIgnoreCase(name.replace("_", ""))) {
                return field;
            }
        }
        return null;
    }
}
