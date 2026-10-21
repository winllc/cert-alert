package com.winllc.certalert.webhook;

import com.winllc.certalert.net.AddressRange;
import com.winllc.certalert.revocation.FetchPolicy;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Where a webhook may be posted. An administrator types the URL, but what it resolves to is
 * up to whoever runs its DNS, so the rules are about addresses as much as names:
 *
 * <ul>
 *   <li>https only, with a host and no credentials in the URL;
 *   <li>where {@code cert-alert.webhooks.allowed-hosts} is set, only the hosts it names (by
 *       name, {@code *.suffix}, or address range);
 *   <li>never loopback, link-local (169.254.0.0/16, fe80::/10 - cloud metadata among them),
 *       a cloud metadata address, an unspecified, multicast or broadcast address, nor an
 *       IPv6 address carrying one of those IPv4 addresses inside it - whatever the allowlist
 *       says;
 *   <li>and, with {@code block-private-networks}, no private address either.
 * </ul>
 *
 * <p>Checked when the URL is saved, and again at every send, on the addresses the name
 * resolves to <em>then</em> - and the post goes to the address that was checked, not to a
 * second lookup (see {@link WebhookTransport}). A name that resolved somewhere harmless when
 * it was saved and somewhere else now (DNS rebinding) is refused at the send.
 */
@Component
public class WebhookAddressGuard {

    /** Looks a host up. The JDK's resolver, but a test can stand in for DNS. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final List<AddressRange> PRIVATE = List.of(
            AddressRange.parse("10.0.0.0/8"),
            AddressRange.parse("172.16.0.0/12"),
            AddressRange.parse("192.168.0.0/16"),
            AddressRange.parse("100.64.0.0/10"),
            AddressRange.parse("fc00::/7"),
            AddressRange.parse("fec0::/10"));

    private static final AddressRange IPV4_COMPATIBLE = AddressRange.parse("::/96");
    private static final AddressRange SIX_TO_FOUR = AddressRange.parse("2002::/16");
    private static final AddressRange BROADCAST = AddressRange.parse("255.255.255.255/32");

    private final WebhookProperties properties;
    private final Resolver resolver;
    private final List<String> names = new ArrayList<>();
    private final List<String> suffixes = new ArrayList<>();
    private final List<AddressRange> ranges = new ArrayList<>();

    @Autowired
    public WebhookAddressGuard(WebhookProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    public WebhookAddressGuard(WebhookProperties properties, Resolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
        for (String entry : properties.getAllowedHosts()) {
            String value = entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith("*.")) {
                suffixes.add(value.substring(1));
            } else if (value.contains("/") || value.matches("[0-9.]+") || value.contains(":")) {
                ranges.add(AddressRange.parse(value));
            } else {
                names.add(value);
            }
        }
    }

    /** A webhook URL refused, and why - said to the administrator, and in the delivery log. */
    public static final class RefusedDestinationException extends IOException {
        public RefusedDestinationException(String message) {
            super(message);
        }
    }

    /**
     * The URL as it may be saved: its form, and where it resolves now.
     *
     * @throws RefusedDestinationException where it may not be posted to
     */
    public URI checkUrl(String url) throws IOException {
        URI uri = parse(url);
        resolve(uri);
        return uri;
    }

    /**
     * Looks the host up now, checks every address it resolves to, and returns the one to
     * connect to. Every address has to pass, not just the first: a name that answers with a
     * public address and a private one is not one to post to.
     */
    public InetAddress resolve(URI uri) throws IOException {
        String host = hostOf(uri);
        boolean allowedByName = names.contains(host) || suffixes.stream().anyMatch(s -> host.endsWith(s) && host.length() > s.length());
        if (isRestricted() && !allowedByName && ranges.isEmpty()) {
            throw new RefusedDestinationException(host + " is not in cert-alert.webhooks.allowed-hosts");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IOException("Unknown host " + host, e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IOException("Unknown host " + host);
        }
        for (InetAddress address : addresses) {
            String refusal = refusalFor(address);
            if (refusal != null) {
                throw new RefusedDestinationException(host + " resolves to " + address.getHostAddress() + ", " + refusal);
            }
            if (isRestricted() && !allowedByName && ranges.stream().noneMatch(range -> range.matches(address))) {
                throw new RefusedDestinationException(host + " is not in cert-alert.webhooks.allowed-hosts");
            }
        }
        return addresses[0];
    }

    /** Why this address is never posted to, or null where it may be. */
    String refusalFor(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return "a loopback address, which webhooks are never sent to";
        }
        if (FetchPolicy.isNeverFetched(address) || BROADCAST.matches(address)) {
            return "a link-local, metadata, unspecified or multicast address, which webhooks are never sent to";
        }
        InetAddress embedded = embeddedIpv4(address);
        if (embedded != null && refusalFor(embedded) != null) {
            return "an IPv6 address carrying " + embedded.getHostAddress() + ", which webhooks are never sent to";
        }
        if (properties.isBlockPrivateNetworks()) {
            if (PRIVATE.stream().anyMatch(range -> range.matches(address))
                    || (embedded != null && PRIVATE.stream().anyMatch(range -> range.matches(embedded)))) {
                return "a private address, and cert-alert.webhooks.block-private-networks is on";
            }
        }
        return null;
    }

    private boolean isRestricted() {
        return !names.isEmpty() || !suffixes.isEmpty() || !ranges.isEmpty();
    }

    /** The IPv4 address an IPv4-compatible (::a.b.c.d) or 6to4 (2002:aabb:ccdd::) address carries. */
    private static InetAddress embeddedIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return null;
        }
        byte[] bytes = address.getAddress();
        try {
            if (IPV4_COMPATIBLE.matches(address)) {
                return Inet4Address.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
            }
            if (SIX_TO_FOUR.matches(address)) {
                return Inet4Address.getByAddress(Arrays.copyOfRange(bytes, 2, 6));
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    static URI parse(String url) throws RefusedDestinationException {
        if (url == null || url.isBlank() || url.length() > 2000) {
            throw new RefusedDestinationException("Give the webhook an https URL of up to 2000 characters");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new RefusedDestinationException("Not a URL: " + e.getReason());
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new RefusedDestinationException("Webhooks are sent over https only");
        }
        if (uri.getRawUserInfo() != null) {
            throw new RefusedDestinationException("A webhook URL may not carry a user name or password");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new RefusedDestinationException("A webhook URL needs a host");
        }
        if (uri.getRawFragment() != null) {
            throw new RefusedDestinationException("A webhook URL may not have a fragment");
        }
        return uri;
    }

    static String hostOf(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
