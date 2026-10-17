package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which connections a forwarded certificate is taken from (ENS_X509_TRUSTED_PROXIES): the
 * connection's own address, held to each range within its own address family.
 */
class TrustedProxyTest {

    private static ForwardedCertificates trusting(String proxies) {
        EnsX509Properties settings = new EnsX509Properties();
        settings.setTrustedProxies(proxies);
        return new ForwardedCertificates(settings, new TrustedCas(settings));
    }

    @Test
    void aProxyInTheRangeIsTrusted() {
        ForwardedCertificates forwarded = trusting("10.0.0.0/8, fd00::/8");

        assertThat(forwarded.fromTrustedProxy("10.42.0.7")).isTrue();
        assertThat(forwarded.fromTrustedProxy("fd00::7")).isTrue();
        assertThat(forwarded.fromTrustedProxy("192.168.1.7")).isFalse();
    }

    @Test
    void anIpv6PeerIsNotInAnIpv4RangeThatSharesItsLeadingBits() {
        // a00::5 begins with the byte 10.0.0.0/8 does. Matching by leading bits alone, as
        // Spring Security's IpAddressMatcher does, would believe its header.
        ForwardedCertificates forwarded = trusting("10.0.0.0/8");

        assertThat(forwarded.fromTrustedProxy("a00::5")).isFalse();
        assertThat(forwarded.fromTrustedProxy("0:0:0:0:0:0:0:1")).isFalse();
    }

    @Test
    void anEntryThatIsNotAnAddressTrustsNobody() {
        ForwardedCertificates forwarded = trusting("ingress.example.gov");

        assertThat(forwarded.fromTrustedProxy("10.0.0.1")).isFalse();
        assertThat(forwarded.fromTrustedProxy(null)).isFalse();
    }
}
