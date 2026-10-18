package com.winllc.certalert.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * An IP address or CIDR range, written as a literal, matched only against addresses of its
 * own family.
 *
 * <p>Spring Security's {@code IpAddressMatcher} compares the leading bits of whatever it is
 * handed, so an IPv6 address is held against an IPv4 range by its first bytes: {@code ::1}
 * falls in {@code 0.0.0.0/8}, and {@code a00::1} in {@code 10.0.0.0/8}. Where the range is an
 * allowlist - revocation hosts, trusted proxies - that lets in addresses nobody named, so the
 * ranges here never cross families. An IPv4-mapped IPv6 address ({@code ::ffff:10.0.0.1}) is
 * the IPv4 address it carries, which is how the JDK reads one.
 *
 * <p>Literals only: a host name is refused rather than looked up, so building one never
 * touches DNS.
 */
public final class AddressRange {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*(%[\\w.-]+)?");

    private final String text;
    private final byte[] network;
    private final int prefix;

    private AddressRange(String text, byte[] network, int prefix) {
        this.text = text;
        this.network = network;
        this.prefix = prefix;
    }

    /**
     * Reads {@code 10.0.0.0/8}, {@code 127.0.0.1}, {@code fd00::/8}, {@code ::1} or
     * {@code [::1]}; a single address is a range of one.
     *
     * @throws IllegalArgumentException for anything else, host names included
     */
    public static AddressRange parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("no address");
        }
        String trimmed = value.trim();
        int slash = trimmed.indexOf('/');
        String address = slash < 0 ? trimmed : trimmed.substring(0, slash);
        InetAddress parsed = literal(address);
        if (parsed == null) {
            throw new IllegalArgumentException("'" + value + "' is not an IP address or CIDR range");
        }
        byte[] bytes = parsed.getAddress();
        int bits = bytes.length * 8;
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + value + "' has no prefix length after the /", e);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException(
                        "'" + value + "' has a prefix length outside 0-" + bits);
            }
        }
        return new AddressRange(trimmed, mask(bytes, prefix), prefix);
    }

    /** Whether the address is in this range; never for an address of the other family. */
    public boolean matches(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == network.length && Arrays.equals(mask(bytes, prefix), network);
    }

    /** The same, for an address written as a literal; false for anything that is not one. */
    public boolean matches(String address) {
        return address != null && matches(literal(address.trim()));
    }

    /**
     * The address a literal names, or null where it is not one. Checked against the shape
     * of an address first, because {@link InetAddress#getByName} would otherwise resolve a
     * name.
     */
    public static InetAddress literal(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.startsWith("[") && value.endsWith("]")
                ? value.substring(1, value.length() - 1)
                : value;
        try {
            if (IPV4.matcher(candidate).matches()) {
                // Built from the octets, so "1.2.3.400" is refused here rather than handed
                // to a resolver as a name.
                String[] octets = candidate.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(octets[i]);
                    if (octet > 255) {
                        return null;
                    }
                    bytes[i] = (byte) octet;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (IPV6.matcher(candidate).matches()) {
                // Holds a colon, so the JDK parses it as an IPv6 literal and refuses it if it
                // is not one, without a lookup.
                return InetAddress.getByName(candidate);
            }
            return null;
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] masked = Arrays.copyOf(address, address.length);
        for (int i = 0; i < masked.length; i++) {
            int keep = Math.max(0, Math.min(8, prefix - i * 8));
            masked[i] &= (byte) (0xff << (8 - keep));
        }
        return masked;
    }

    @Override
    public String toString() {
        return text;
    }
}
