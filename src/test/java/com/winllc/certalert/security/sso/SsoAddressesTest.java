package com.winllc.certalert.security.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The addresses single sign-on is given come from configuration, never from a request: a
 * public URL that is not an absolute address, or an entity id that is a template, is refused.
 */
class SsoAddressesTest {

    private static SsoProperties at(String publicUrl) {
        SsoProperties properties = new SsoProperties();
        properties.setPublicUrl(publicUrl);
        return properties;
    }

    @Test
    void thePublicUrlMustBeAnAbsoluteAddress() {
        assertThat(at("https://certalert.example.gov/").requirePublicUrl()).isEqualTo("https://certalert.example.gov");
        for (String bad : new String[] {null, "", "{baseUrl}", "/relative", "certalert.example.gov",
                "ftp://certalert.example.gov", "https://certalert.example.gov/?x=1"}) {
            assertThatThrownBy(() -> at(bad).requirePublicUrl()).as(String.valueOf(bad))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void theEntityIdIsTheConfiguredOneOrBuiltFromThePublicUrl() {
        SsoProperties.Saml saml = new SsoProperties.Saml();
        assertThat(SsoSignIn.entityIdOf(saml, "https://certalert.example.gov"))
                .isEqualTo("https://certalert.example.gov/saml2/service-provider-metadata/saml");
        saml.setEntityId("urn:example:certalert");
        assertThat(SsoSignIn.entityIdOf(saml, "https://certalert.example.gov")).isEqualTo("urn:example:certalert");
        saml.setEntityId("{baseUrl}/saml2/service-provider-metadata/{registrationId}");
        assertThatThrownBy(() -> SsoSignIn.entityIdOf(saml, "https://certalert.example.gov"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aProvidersGroupsAreMatchedAgainstItsOwnListsOnly() {
        SsoProperties.Oidc oidc = new SsoProperties.Oidc();
        oidc.getAdminGroups().add("CN=CertAlert Admins, OU=Groups, DC=example, DC=gov");
        assertThat(SsoIdentityResolver.rolesFrom(
                        java.util.List.of("cn=certalert admins,ou=groups,dc=example,dc=gov"), oidc))
                .containsExactly("ROLE_ADMIN");
        assertThat(SsoIdentityResolver.rolesFrom(java.util.List.of("admins"), oidc)).isEmpty();
        assertThat(SsoIdentityResolver.rolesFrom(java.util.List.of("anything"), new SsoProperties.Saml())).isEmpty();
    }
}
