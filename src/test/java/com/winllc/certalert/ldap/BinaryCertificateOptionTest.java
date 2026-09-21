package com.winllc.certalert.ldap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which name the certificate attribute is asked for by.
 *
 * <p>RFC 4522 says a certificate is transferred in binary and a client says so by asking
 * for {@code userCertificate;binary}. Servers that never implemented the rule - a virtual
 * directory presenting an attribute it assembled from elsewhere, Radiant Logic FID 7.4
 * among them - have nothing under that name and answer with an empty result, so every
 * entry syncs with no certificates at all. The toggle is for them.
 */
class BinaryCertificateOptionTest {

    @Test
    void theOptionIsAppendedWhenItIsWanted() {
        assertThat(LdapAttributes.requestName("userCertificate", true)).isEqualTo("userCertificate;binary");
    }

    @Test
    void andTheNameIsSentPlainWhenItIsNot() {
        assertThat(LdapAttributes.requestName("userCertificate", false)).isEqualTo("userCertificate");
    }

    /** Asking for it twice would name an attribute no server has. */
    @Test
    void aNameThatAlreadySaysBinaryIsLeftAlone() {
        assertThat(LdapAttributes.requestName("userCertificate;binary", true)).isEqualTo("userCertificate;binary");
        assertThat(LdapAttributes.requestName("USERCERTIFICATE;BINARY", true)).isEqualTo("USERCERTIFICATE;BINARY");
    }

    /**
     * A configured name may be blank, which means the attribute is not mapped at all. It
     * has to stay blank so the caller drops it rather than asking for ";binary".
     */
    @Test
    void anUnmappedAttributeIsNotTurnedIntoOne() {
        assertThat(LdapAttributes.requestName("", true)).isEmpty();
        assertThat(LdapAttributes.requestName(null, true)).isNull();
        assertThat(LdapAttributes.requestName(null, false)).isNull();
    }

    /** What the sweep and the changelog connector actually put on the wire. */
    @Test
    void theToggleReachesTheRequestedAttributes() {
        LdapProperties properties = new LdapProperties();

        properties.setBinaryCertificateOption(true);
        DirectoryEntryMapper binary = new DirectoryEntryMapper(properties);
        assertThat(binary.userAttributes()).contains("userCertificate;binary");
        assertThat(binary.serverAttributes()).contains("userCertificate;binary");

        properties.setBinaryCertificateOption(false);
        DirectoryEntryMapper plain = new DirectoryEntryMapper(properties);
        assertThat(plain.userAttributes()).contains("userCertificate").doesNotContain("userCertificate;binary");
        assertThat(plain.serverAttributes()).contains("userCertificate").doesNotContain("userCertificate;binary");
    }
}
