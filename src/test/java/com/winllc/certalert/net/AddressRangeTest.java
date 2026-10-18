package com.winllc.certalert.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Ranges hold only addresses of their own family. Spring Security's IpAddressMatcher
 * compares leading bits whatever the family, which put ::1 in 0.0.0.0/8 - and so refused
 * loopback on any host where localhost also resolves to ::1 - and would put a00::1 in an
 * allowlisted 10.0.0.0/8.
 */
class AddressRangeTest {

    @Test
    void anIpv6AddressIsNeverInAnIpv4Range() {
        assertThat(AddressRange.parse("0.0.0.0/8").matches("::1")).isFalse();
        assertThat(AddressRange.parse("0.0.0.0/8").matches("0:0:0:0:0:0:0:1")).isFalse();
        assertThat(AddressRange.parse("10.0.0.0/8").matches("a00::1")).isFalse();
        assertThat(AddressRange.parse("127.0.0.0/8").matches("7f00::1")).isFalse();
        assertThat(AddressRange.parse("0.0.0.0/0").matches("::1")).isFalse();
    }

    @Test
    void norAnIpv4AddressInAnIpv6Range() {
        assertThat(AddressRange.parse("::/0").matches("10.1.2.3")).isFalse();
        assertThat(AddressRange.parse("a00::/8").matches("10.1.2.3")).isFalse();
    }

    @Test
    void addressesInTheirOwnFamilyAreMatchedByPrefix() {
        AddressRange ten = AddressRange.parse("10.20.0.0/16");
        assertThat(ten.matches("10.20.255.1")).isTrue();
        assertThat(ten.matches("10.21.0.1")).isFalse();

        AddressRange ula = AddressRange.parse("fd00::/8");
        assertThat(ula.matches("fd12:3456::1")).isTrue();
        assertThat(ula.matches("fe80::1")).isFalse();

        assertThat(AddressRange.parse("10.0.0.0/12").matches("10.15.255.255")).isTrue();
        assertThat(AddressRange.parse("10.0.0.0/12").matches("10.16.0.0")).isFalse();
    }

    @Test
    void aSingleAddressIsARangeOfOne() {
        assertThat(AddressRange.parse("127.0.0.1").matches("127.0.0.1")).isTrue();
        assertThat(AddressRange.parse("127.0.0.1").matches("127.0.0.2")).isFalse();
        assertThat(AddressRange.parse("[::1]").matches("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(AddressRange.parse("::1").matches("::2")).isFalse();
    }

    @Test
    void anIpv4MappedAddressIsTheIpv4AddressItCarries() {
        assertThat(AddressRange.parse("10.0.0.0/8").matches("::ffff:10.1.2.3")).isTrue();
    }

    @Test
    void namesAndMalformedRangesAreRefusedWithoutALookup() {
        assertThatThrownBy(() -> AddressRange.parse("localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AddressRange.parse("proxy.example.gov/24"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AddressRange.parse("10.0.0.400")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AddressRange.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AddressRange.parse("::/129")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AddressRange.parse("10.0.0.0/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AddressRange.parse(" ")).isInstanceOf(IllegalArgumentException.class);

        assertThat(AddressRange.parse("10.0.0.0/8").matches("localhost")).isFalse();
        assertThat(AddressRange.parse("10.0.0.0/8").matches((String) null)).isFalse();
    }
}
