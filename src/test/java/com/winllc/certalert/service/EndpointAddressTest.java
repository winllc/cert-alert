package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.domain.DirectoryServer;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Where a probe goes, worked out from the entry rather than from the request.
 *
 * <p>The host is never the caller's to choose. A port is a choice between the ports of a
 * machine the directory already names; a host would be a choice of machine, which would
 * make this a way to reach anything the application can reach.
 */
class EndpointAddressTest {

    @Test
    void theUrlDecidesTheHostAndItsOwnPort() {
        DirectoryServer server = server("https://web01.example.gov:8443/health", null);

        assertThat(EndpointAddress.of(server, null, 443))
                .contains(new EndpointAddress("web01.example.gov", 8443));
    }

    @Test
    void aUrlWithNoPortFallsBackToTheDefault() {
        DirectoryServer server = server("https://web01.example.gov/", null);

        assertThat(EndpointAddress.of(server, null, 443))
                .contains(new EndpointAddress("web01.example.gov", 443));
    }

    @Test
    void theCallerChoosesThePortAndNeverTheHost() {
        DirectoryServer server = server("https://web01.example.gov:8443/", null);

        assertThat(EndpointAddress.of(server, 9443, 443))
                .contains(new EndpointAddress("web01.example.gov", 9443));
    }

    @Test
    void theAddressIsTheFallbackWhenThereIsNoUrl() {
        DirectoryServer server = server(null, "10.1.2.11");

        assertThat(EndpointAddress.of(server, null, 443)).contains(new EndpointAddress("10.1.2.11", 443));
    }

    @Test
    void somethingThatIsNotAUrlDoesNotLoseTheAddress() {
        // Directories hold what they are given, and serverURL is a free-text attribute.
        DirectoryServer server = server("web01, port 443", "10.1.2.11");

        assertThat(EndpointAddress.of(server, null, 443)).contains(new EndpointAddress("10.1.2.11", 443));
    }

    @Test
    void anEntryThatSaysWhereNothingIsCannotBeProbed() {
        assertThat(EndpointAddress.of(server(null, null), null, 443)).isEqualTo(Optional.empty());
        assertThat(EndpointAddress.of(server("", "  "), null, 443)).isEqualTo(Optional.empty());
    }

    @Test
    void aPortThatIsNotAPortIsRefused() {
        DirectoryServer server = server("https://web01.example.gov/", null);

        assertThatThrownBy(() -> EndpointAddress.of(server, 0, 443))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointAddress.of(server, 70000, 443))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Built by hand: the setters belong to the sync, and this is about the reading. */
    private static DirectoryServer server(String url, String address) {
        DirectoryServer server = new DirectoryServer("cn=web01,ou=servers,dc=example,dc=test");
        set(server, "serverUrl", url);
        set(server, "icServerAddress", address);
        return server;
    }

    private static void set(DirectoryServer server, String field, String value) {
        try {
            Field declared = DirectoryServer.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(server, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set " + field, e);
        }
    }
}
