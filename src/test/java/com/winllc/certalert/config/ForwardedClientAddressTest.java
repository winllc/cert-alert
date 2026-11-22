package com.winllc.certalert.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.net.AddressRange;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The client's address behind proxies: the rightmost {@code X-Forwarded-For} hop that is not a
 * trusted proxy, never the leftmost value a client wrote itself.
 */
class ForwardedClientAddressTest {

    private static final List<AddressRange> TRUSTED = List.of(AddressRange.parse("10.0.0.0/8"), AddressRange.parse("::1"));

    private static HttpServletRequest seenBehind(String peer, String... forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users");
        request.setRemoteAddr(peer);
        for (String value : forwardedFor) {
            request.addHeader("X-Forwarded-For", value);
        }
        request.addHeader("X-Forwarded-Proto", "https");
        MockFilterChain chain = new MockFilterChain();
        new ForwardedHeadersConfig.HostPinningForwardedHeaderFilter(TRUSTED)
                .doFilter(request, new MockHttpServletResponse(), chain);
        ServletRequest seen = chain.getRequest();
        return (HttpServletRequest) seen;
    }

    @Test
    void aClientsOwnValueToTheLeftOfWhatTheProxyAppendedIsIgnored() throws Exception {
        HttpServletRequest seen = seenBehind("10.0.0.5", "6.6.6.6, 203.0.113.9");
        assertThat(seen.getRemoteAddr()).isEqualTo("203.0.113.9");
        assertThat(seen.getRemoteHost()).isEqualTo("203.0.113.9");
        // Still https, from the proxy's X-Forwarded-Proto.
        assertThat(seen.isSecure()).isTrue();
        // And nothing further on reads the header itself.
        assertThat(seen.getHeader("X-Forwarded-For")).isNull();
    }

    @Test
    void trustedProxiesInTheChainAreSteppedOver() throws Exception {
        assertThat(seenBehind("10.0.0.5", "203.0.113.9, 10.1.2.3").getRemoteAddr()).isEqualTo("203.0.113.9");
        // Across more than one header line, and with ports.
        assertThat(seenBehind("10.0.0.5", "198.51.100.1:4711", "10.1.2.3").getRemoteAddr()).isEqualTo("198.51.100.1");
        assertThat(seenBehind("::1", "[2001:db8::7]:443").getRemoteAddr()).isEqualTo("2001:db8::7");
        // Nothing but proxies: the furthest of them.
        assertThat(seenBehind("10.0.0.5", "10.9.9.9, 10.1.2.3").getRemoteAddr()).isEqualTo("10.9.9.9");
    }

    @Test
    void aConnectionNotFromATrustedProxyIsTheClientWhateverItSays() throws Exception {
        assertThat(seenBehind("198.51.100.20", "1.2.3.4").getRemoteAddr()).isEqualTo("198.51.100.20");
        // An IPv6 peer is never "in" an IPv4 range.
        assertThat(seenBehind("a00::1", "1.2.3.4").getRemoteAddr()).isEqualTo("a00::1");
    }

    @Test
    void aHopThatIsNotAnAddressStopsAtTheProxyThatPassedItOn() throws Exception {
        assertThat(seenBehind("10.0.0.5", "203.0.113.9, unknown").getRemoteAddr()).isEqualTo("10.0.0.5");
        assertThat(seenBehind("10.0.0.5", "evil.example, 10.1.2.3").getRemoteAddr()).isEqualTo("10.1.2.3");
    }

    @Test
    void trustingNoProxyIsTheConnectionsOwnAddress() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6");
        MockFilterChain chain = new MockFilterChain();
        new ForwardedHeadersConfig.HostPinningForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest().getRemoteAddr()).isEqualTo("127.0.0.1");
    }
}
