package com.winllc.certalert.ldap;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.EmailAddresses;
import org.junit.jupiter.api.Test;

/**
 * What a {@code serverPOC} value is taken to mean.
 *
 * <p>Two things go wrong with the attribute in practice. It is multi-valued, so three
 * contacts can be three values - but a directory filled in through a form often carries
 * them as one value, comma-separated, and read whole that matches nobody: the server looks
 * like it has no contacts and nobody is told when its certificate runs out. And where the
 * convention is addresses, a value that is not one cannot be written to at all.
 */
class ServerPocParsingTest {

    @Test
    void severalContactsInOneValueBecomeOneEach() {
        assertThat(EmailAddresses.split("ops@example.gov,duty@example.gov"))
                .containsExactly("ops@example.gov", "duty@example.gov");
    }

    /** Somebody typing a list puts a space after the comma; that space is not a contact. */
    @Test
    void andTheSpaceAfterTheCommaIsNotPartOfTheAddress() {
        assertThat(EmailAddresses.split("ops@example.gov, duty@example.gov ,  night@example.gov"))
                .containsExactly("ops@example.gov", "duty@example.gov", "night@example.gov");
    }

    /** The ordinary case is one address and no comma, which has to come back untouched. */
    @Test
    void oneAddressIsLeftAlone() {
        assertThat(EmailAddresses.split("ops@example.gov")).containsExactly("ops@example.gov");
    }

    @Test
    void andNothingIsNobody() {
        assertThat(EmailAddresses.split(null)).isEmpty();
        assertThat(EmailAddresses.split("")).isEmpty();
        assertThat(EmailAddresses.split("  ")).isEmpty();
        // A trailing comma is a typing slip, not an empty contact.
        assertThat(EmailAddresses.split("ops@example.gov,")).containsExactly("ops@example.gov");
        assertThat(EmailAddresses.split(",,")).isEmpty();
    }

    @Test
    void anAddressIsSomethingWithAnAtSignAndADotAfterIt() {
        assertThat(EmailAddresses.isAddress("ops@example.gov")).isTrue();
        assertThat(EmailAddresses.isAddress("  ops@example.gov  ")).isTrue();
        assertThat(EmailAddresses.isAddress("Duty Officer")).isFalse();
        assertThat(EmailAddresses.isAddress("ops@example")).isFalse();
        assertThat(EmailAddresses.isAddress("ops.example.gov")).isFalse();
        assertThat(EmailAddresses.isAddress("two addresses@example.gov")).isFalse();
        assertThat(EmailAddresses.isAddress(null)).isFalse();
    }
}
