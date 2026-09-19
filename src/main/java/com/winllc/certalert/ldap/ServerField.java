package com.winllc.certalert.ldap;

import java.util.function.Function;

/**
 * The IC Non-Person Entity attributes this application reads, each paired with the
 * property that holds its directory attribute name.
 */
public enum ServerField {
    COMMON_NAME(LdapProperties.Server::getCommonName),
    UID(LdapProperties.Server::getUid),
    GIVEN_NAME(LdapProperties.Server::getGivenName),
    DESCRIPTION(LdapProperties.Server::getDescription),
    SERVER_URL(LdapProperties.Server::getServerUrl),
    IC_SERVER_ADDRESS(LdapProperties.Server::getIcServerAddress),
    ATO_STATUS(LdapProperties.Server::getAtoStatus),
    LIFE_CYCLE_STATUS(LdapProperties.Server::getLifeCycleStatus),
    EMPLOYEE_TYPE(LdapProperties.Server::getEmployeeType),
    COUNTRY_OF_AFFILIATION(LdapProperties.Server::getCountryOfAffiliation),
    DUTY_ORGANIZATION(LdapProperties.Server::getDutyOrganization),
    DUTY_SUB_ORGANIZATION(LdapProperties.Server::getDutySubOrganization),
    ADMIN_ORGANIZATION(LdapProperties.Server::getAdminOrganization),
    IS_IC_MEMBER(LdapProperties.Server::getIcMember),
    IC_NETWORKS(LdapProperties.Server::getIcNetworks),
    RESOURCE_SECURITY_MARK(LdapProperties.Server::getResourceSecurityMark),
    ORGANIZATION(LdapProperties.Server::getOrganization),
    ORGANIZATIONAL_UNIT(LdapProperties.Server::getOrganizationalUnit);

    private final Function<LdapProperties.Server, String> attributeName;

    ServerField(Function<LdapProperties.Server, String> attributeName) {
        this.attributeName = attributeName;
    }

    /** The directory attribute this field is currently mapped to. */
    public String attributeName(LdapProperties.Server mapping) {
        return attributeName.apply(mapping);
    }
}
