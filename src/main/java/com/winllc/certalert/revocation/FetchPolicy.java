package com.winllc.certalert.revocation;

import com.winllc.certalert.net.AddressRange;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Where revocation data may be fetched from.
 *
 * <p>The addresses come out of certificates - a distribution point, a responder - and a
 * certificate is something somebody else wrote. One that names
 * {@code http://169.254.169.254/latest/meta-data/} is asking this application to read its
 * own cloud credentials and report on what came back; one that names
 * {@code http://localhost:9090/actuator/...} is asking it to talk to itself. Neither is a
 * question about revocation, so both are refused before anything is sent.
 *
 * <p>Three rules, in order:
 *
 * <ul>
 *   <li>Only http and https - and ldap and ldaps for where a list is published in a
 *       directory - for the address a certificate names, and only http and https for
 *       wherever that redirects to.
 *   <li>When an allowlist is configured, only the hosts it names. The hosts of the responder
 *       and list configured on this deployment are always allowed: somebody chose them.
 *   <li>Never link-local or a cloud metadata address, whatever the allowlist says, and
 *       never loopback unless the host is allowed by name: a relay on the same host is
 *       somebody's decision, and a certificate pointing there is not.
 * </ul>
 *
 * <p>The check resolves the name itself, so it is a check of where the name points now.
 * The client resolves it again to connect; the JVM's own address cache normally gives it
 * the same answer, but a name that changes in between is not something this can rule out,
 * which is what the allowlist is for where it matters.
 */
public final class FetchPolicy {

    /** Schemes a certificate may name. */
    private static final Set<String> FIRST_SCHEMES = Set.of("http", "https", "ldap", "ldaps");

    /** Schemes a redirect may lead to: never file:, jar:, ftp: or the like. */
    private static final Set<String> REDIRECT_SCHEMES = Set.of("http", "https");

    /**
     * Instance metadata services. 169.254.169.254 (AWS, Azure, GCP, OpenStack, Oracle) is
     * link-local and refused as that; these are the ones that are not.
     */
    private static final List<AddressRange> METADATA = List.of(
            AddressRange.parse("fd00:ec2::254/128"),
            AddressRange.parse("100.100.100.200/32"),
            // Azure's WireServer: the host agent's channel, metadata and extensions included.
            AddressRange.parse("168.63.129.16/32"),
            // "This network": 0.0.0.0 reaches the local host on Linux.
            AddressRange.parse("0.0.0.0/8"));

    /** NAT64's well-known prefix, which carries an IPv4 address in its last 32 bits. */
    private static final AddressRange NAT64 = AddressRange.parse("64:ff9b::/96");

    private final List<String> names;
    private final List<String> suffixes;
    private final List<AddressRange> ranges;
    private final Set<String> configuredHosts;

    private FetchPolicy(Collection<String> allowed, Collection<String> configuredUrls) {
        List<String> names = new ArrayList<>();
        List<String> suffixes = new ArrayList<>();
        List<AddressRange> ranges = new ArrayList<>();
        for (String entry : allowed) {
            String value = entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith("*.")) {
                suffixes.add(value.substring(1));
            } else if (value.contains("/") || isAddressLiteral(value)) {
                ranges.add(AddressRange.parse(value));
            } else {
                names.add(value);
            }
        }
        this.names = List.copyOf(names);
        this.suffixes = List.copyOf(suffixes);
        this.ranges = List.copyOf(ranges);
        Set<String> configured = new LinkedHashSet<>();
        for (String url : configuredUrls) {
            String host = hostOf(url);
            if (host != null) {
                configured.add(host);
            }
        }
        this.configuredHosts = Set.copyOf(configured);
    }

    /**
     * @param allowed the allowlist; empty allows any host the address rules do
     * @param configuredUrls responders and lists configured on this deployment, whose hosts
     *     are allowed whatever the allowlist says
     */
    public static FetchPolicy of(Collection<String> allowed, String... configuredUrls) {
        return new FetchPolicy(
                allowed == null ? List.of() : allowed,
                Arrays.stream(configuredUrls).filter(Objects::nonNull).toList());
    }

    /** Whether an allowlist is configured at all. */
    public boolean isRestricted() {
        return !names.isEmpty() || !suffixes.isEmpty() || !ranges.isEmpty();
    }

    /**
     * Refuses an address a certificate names, or one configured here.
     *
     * @throws RefusedDestinationException where it may not be fetched from
     */
    public void check(URI uri) throws IOException {
        check(uri, FIRST_SCHEMES);
    }

    /**
     * Refuses where a redirect leads, which is held to more than the first address: http and
     * https only, and then the same rules as anywhere else.
     */
    public void checkRedirect(URI uri) throws IOException {
        check(uri, REDIRECT_SCHEMES);
    }

    /** The same rules, for a host and port taken out of a URL some other way. */
    public void checkHost(String host) throws IOException {
        if (host == null || host.isBlank()) {
            throw new RefusedDestinationException("no host to fetch from");
        }
        String name = stripBrackets(host.trim().toLowerCase(Locale.ROOT));
        boolean allowedByName = configuredHosts.contains(name) || allowedByName(name);
        if (isRestricted() && !allowedByName && ranges.isEmpty()) {
            // Nothing it could resolve to would be allowed, so there is nothing to look up.
            throw new RefusedDestinationException(name + " is not in cert-alert.revocation.allowed-hosts");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(name);
        } catch (UnknownHostException e) {
            throw new IOException("Unknown host " + name, e);
        }
        for (InetAddress address : addresses) {
            if (isNeverFetched(address)) {
                throw new RefusedDestinationException(
                        name + " resolves to " + address.getHostAddress()
                                + ", a link-local or metadata address that is never fetched from");
            }
            boolean allowed = allowedByName || inRange(address);
            if (address.isLoopbackAddress() && !allowed) {
                throw new RefusedDestinationException(name + " resolves to " + address.getHostAddress()
                        + ", a loopback address; name the host in cert-alert.revocation.allowed-hosts to "
                        + "fetch from it");
            }
            if (isRestricted() && !allowed) {
                throw new RefusedDestinationException(
                        name + " is not in cert-alert.revocation.allowed-hosts");
            }
        }
    }

    private void check(URI uri, Set<String> schemes) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!schemes.contains(scheme)) {
            throw new RefusedDestinationException(
                    "the " + (scheme.isEmpty() ? "missing" : scheme) + " scheme is not fetched from (" + uri + ")");
        }
        checkHost(uri.getHost());
    }

    private boolean allowedByName(String host) {
        if (names.contains(host)) {
            return true;
        }
        for (String suffix : suffixes) {
            if (host.endsWith(suffix) && host.length() > suffix.length()) {
                return true;
            }
        }
        return false;
    }

    private boolean inRange(InetAddress address) {
        for (AddressRange range : ranges) {
            if (range.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /** Link-local, metadata, unspecified and multicast: refused whatever is configured. */
    public static boolean isNeverFetched(InetAddress address) {
        if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        for (AddressRange metadata : METADATA) {
            if (metadata.matches(address)) {
                return true;
            }
        }
        if (NAT64.matches(address)) {
            byte[] bytes = address.getAddress();
            try {
                InetAddress embedded = Inet4Address.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
                return isNeverFetched(embedded) || embedded.isLoopbackAddress();
            } catch (UnknownHostException e) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAddressLiteral(String value) {
        return value.matches("[0-9.]+") || value.contains(":");
    }

    private static String stripBrackets(String value) {
        return value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : stripBrackets(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An address this application will not fetch from. */
    public static final class RefusedDestinationException extends IOException {

        public RefusedDestinationException(String message) {
            super(message);
        }
    }
}
