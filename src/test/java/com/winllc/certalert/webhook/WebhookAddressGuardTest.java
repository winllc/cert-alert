package com.winllc.certalert.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Where a webhook may go: the URL's form, and every address its name resolves to - with DNS
 * stood in for, so a name can be made to resolve anywhere.
 */
class WebhookAddressGuardTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static WebhookAddressGuard guard(WebhookProperties properties, Map<String, List<String>> dns) {
        return new WebhookAddressGuard(properties, host -> {
            List<String> answers = dns.get(host);
            if (answers == null) {
                throw new UnknownHostException(host);
            }
            return answers.stream().map(WebhookAddressGuardTest::ip).toArray(InetAddress[]::new);
        });
    }

    private static WebhookAddressGuard guard(Map<String, List<String>> dns) {
        return guard(new WebhookProperties(), dns);
    }

    @Test
    void onlyHttpsWithAHostAndNoCredentials() {
        WebhookAddressGuard guard = guard(Map.of("hooks.example.org", List.of("93.184.216.34")));
        assertThatThrownBy(() -> guard.checkUrl("http://hooks.example.org/x")).hasMessageContaining("https only");
        assertThatThrownBy(() -> guard.checkUrl("ftp://hooks.example.org/x")).hasMessageContaining("https only");
        assertThatThrownBy(() -> guard.checkUrl("https://user:pw@hooks.example.org/x")).hasMessageContaining("user name");
        assertThatThrownBy(() -> guard.checkUrl("https:///x")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> guard.checkUrl("file:///etc/passwd")).hasMessageContaining("https only");
    }

    @Test
    void aPublicAddressIsAllowed() throws Exception {
        WebhookAddressGuard guard = guard(Map.of("hooks.example.org", List.of("93.184.216.34")));
        assertThat(guard.checkUrl("https://hooks.example.org/certalert")).hasHost("hooks.example.org");
        assertThat(guard.resolve(URI.create("https://hooks.example.org/x"))).isEqualTo(ip("93.184.216.34"));
    }

    @Test
    void loopbackLinkLocalAndMetadataAreRefusedHoweverTheyAreWritten() {
        WebhookAddressGuard guard = guard(Map.of(
                "localhost", List.of("127.0.0.1"),
                "loop6", List.of("::1"),
                "metadata", List.of("169.254.169.254"),
                "metadata6", List.of("fd00:ec2::254"),
                "alibaba", List.of("100.100.100.200"),
                "linklocal6", List.of("fe80::1"),
                "zero", List.of("0.0.0.0"),
                "mapped", List.of("::ffff:127.0.0.1"),
                "compatible", List.of("::169.254.169.254"),
                "sixtofour", List.of("2002:a9fe:a9fe::1")));
        for (String host : List.of("localhost", "loop6", "metadata", "metadata6", "alibaba", "linklocal6", "zero",
                "mapped", "compatible", "sixtofour")) {
            assertThatThrownBy(() -> guard.checkUrl("https://" + host + "/x"))
                    .as(host)
                    .isInstanceOf(WebhookAddressGuard.RefusedDestinationException.class);
        }
        // Address literals are the same addresses.
        for (String url : List.of("https://127.0.0.1/x", "https://[::1]/x", "https://169.254.169.254/latest/meta-data/",
                "https://[::ffff:169.254.169.254]/x", "https://[fe80::1]/x")) {
            WebhookAddressGuard literal = new WebhookAddressGuard(new WebhookProperties());
            assertThatThrownBy(() -> literal.checkUrl(url)).as(url)
                    .isInstanceOf(WebhookAddressGuard.RefusedDestinationException.class);
        }
    }

    @Test
    void translatedAndTunnelledIpv6IsRefusedAndNat64IsReadForWhatItCarries() throws Exception {
        WebhookAddressGuard guard = guard(Map.of(
                "teredo", List.of("2001:0:4136:e378:8000:63bf:3fff:fdd2"),
                "localnat64", List.of("64:ff9b:1::a00:1"),
                "nat64metadata", List.of("64:ff9b::a9fe:a9fe"),
                "nat64private", List.of("64:ff9b::a01:203"),
                "nat64public", List.of("64:ff9b::5db8:d822"),
                "azure", List.of("168.63.129.16")));
        for (String host : List.of("teredo", "localnat64", "nat64metadata", "azure")) {
            assertThatThrownBy(() -> guard.checkUrl("https://" + host + "/x")).as(host)
                    .isInstanceOf(WebhookAddressGuard.RefusedDestinationException.class);
        }
        assertThat(guard.checkUrl("https://nat64public/x")).isNotNull();
        assertThat(guard.checkUrl("https://nat64private/x")).isNotNull();
        WebhookProperties blocking = new WebhookProperties();
        blocking.setBlockPrivateNetworks(true);
        assertThatThrownBy(() -> guard(blocking, Map.of("nat64private", List.of("64:ff9b::a01:203")))
                .checkUrl("https://nat64private/x")).hasMessageContaining("private");
        // An IPv4-mapped address that arrives as IPv6 rather than as the IPv4 address it is.
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 93;
        mapped[13] = (byte) 184;
        mapped[14] = (byte) 216;
        mapped[15] = 34;
        InetAddress v6 = java.net.Inet6Address.getByAddress(null, mapped, -1);
        assertThat(new WebhookAddressGuard(new WebhookProperties()).refusalFor(v6)).isNotNull();
    }

    @Test
    void everyAddressANameResolvesToHasToPass() {
        WebhookAddressGuard guard = guard(Map.of("split", List.of("93.184.216.34", "169.254.169.254")));
        assertThatThrownBy(() -> guard.checkUrl("https://split/x"))
                .hasMessageContaining("169.254.169.254");
    }

    @Test
    void privateNetworksAreAllowedUnlessBlocked() throws Exception {
        Map<String, List<String>> dns = Map.of("bridge.corp", List.of("10.1.2.3"), "ula", List.of("fd12::1"));
        assertThat(guard(dns).checkUrl("https://bridge.corp/x")).isNotNull();
        WebhookProperties blocking = new WebhookProperties();
        blocking.setBlockPrivateNetworks(true);
        assertThatThrownBy(() -> guard(blocking, dns).checkUrl("https://bridge.corp/x")).hasMessageContaining("private");
        assertThatThrownBy(() -> guard(blocking, dns).checkUrl("https://ula/x")).hasMessageContaining("private");
    }

    @Test
    void anAllowlistNarrowsItButNeverOpensLoopback() throws Exception {
        WebhookProperties properties = new WebhookProperties();
        properties.setAllowedHosts(List.of("hooks.example.org", "*.slack.com", "203.0.113.0/24", "localhost"));
        WebhookAddressGuard guard = guard(properties, Map.of(
                "hooks.example.org", List.of("93.184.216.34"),
                "hooks.slack.com", List.of("52.1.2.3"),
                "elsewhere.example", List.of("93.184.216.35"),
                "inrange.example", List.of("203.0.113.9"),
                "localhost", List.of("127.0.0.1")));
        assertThat(guard.checkUrl("https://hooks.example.org/x")).isNotNull();
        assertThat(guard.checkUrl("https://hooks.slack.com/services/x")).isNotNull();
        assertThat(guard.checkUrl("https://inrange.example/x")).isNotNull();
        assertThatThrownBy(() -> guard.checkUrl("https://elsewhere.example/x")).hasMessageContaining("allowed-hosts");
        assertThatThrownBy(() -> guard.checkUrl("https://localhost/x")).hasMessageContaining("loopback");
    }

    @Test
    void aNameThatChangesWhereItPointsIsCheckedAgainAtEverySend() throws Exception {
        // DNS rebinding: harmless when the URL is saved, the metadata service when it is used.
        String[] answer = {"93.184.216.34"};
        WebhookAddressGuard guard = new WebhookAddressGuard(new WebhookProperties(), host -> new InetAddress[] {ip(answer[0])});
        URI uri = guard.checkUrl("https://rebind.example/x");
        answer[0] = "169.254.169.254";
        assertThatThrownBy(() -> guard.resolve(uri)).isInstanceOf(WebhookAddressGuard.RefusedDestinationException.class);
    }
}
