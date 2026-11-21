package com.winllc.certalert.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import com.winllc.certalert.net.AddressRange;
import com.winllc.certalert.security.EnsX509Properties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * With {@code server.forward-headers-strategy=framework} - which the chart sets behind an
 * ingress or route, and which header-mode client certificates require - Spring Boot's
 * {@link ForwardedHeaderFilter} believes whatever {@code Forwarded}, {@code X-Forwarded-Host}
 * and {@code X-Forwarded-Prefix} a request carries. Ingress controllers commonly pass a
 * client's own copy of these through, so a client could make the application think it was
 * reached at another address.
 *
 * <p>This replaces Spring Boot's filter with one that ignores those three, and keeps
 * {@code X-Forwarded-Proto} and {@code -Port}: what is needed to see that the browser's
 * request was https. Nothing that matters to a sign-in is built from the request's host in
 * any case - single sign-on's addresses come from {@code cert-alert.sso.public-url} - but
 * there is no reason to let a client move it.
 *
 * <p>{@code X-Forwarded-For} is not handed to Spring's filter either, which would take its
 * leftmost value - whatever the client wrote, behind a proxy that appends to the header.
 * The client's address is worked out here instead: from the connection's own address,
 * stepping back through {@code X-Forwarded-For} from the right only while the address in
 * hand is a trusted proxy ({@code ENS_X509_TRUSTED_PROXIES}). The first hop that is not one
 * is the client; a connection that is not from a trusted proxy is the client itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
public class ForwardedHeadersConfig {

    private static final Logger log = LoggerFactory.getLogger(ForwardedHeadersConfig.class);

    /** Never passed to Spring's filter; {@code X-Forwarded-For} is read here, hop by hop. */
    static final Set<String> IGNORED = Set.of("forwarded", "x-forwarded-host", "x-forwarded-prefix", "x-forwarded-for");

    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> hostPinningForwardedHeaderFilter(EnsX509Properties x509) {
        List<AddressRange> trusted = new ArrayList<>();
        for (String range : x509.trustedProxyList()) {
            try {
                trusted.add(AddressRange.parse(range));
            } catch (IllegalArgumentException e) {
                log.warn("ENS_X509_TRUSTED_PROXIES entry '{}' is not an address or CIDR", range);
            }
        }
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new HostPinningForwardedHeaderFilter(trusted));
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Spring's filter, applied to the request with the host-moving headers taken out and the
     * client's address worked out from the trusted proxies.
     */
    public static final class HostPinningForwardedHeaderFilter extends ForwardedHeaderFilter {

        private final List<AddressRange> trustedProxies;

        /** Trusting no proxy: the client is always the connection's own address. */
        public HostPinningForwardedHeaderFilter() {
            this(List.of());
        }

        public HostPinningForwardedHeaderFilter(List<AddressRange> trustedProxies) {
            this.trustedProxies = List.copyOf(trustedProxies);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            super.doFilterInternal(
                    new WithoutHostHeaders(request, clientAddress(request, trustedProxies)), response, chain);
        }
    }

    /**
     * The rightmost address that is not a trusted proxy, starting from the connection's own
     * and stepping left through {@code X-Forwarded-For}. A hop that is not an address stops
     * the walk at the proxy that passed it on.
     */
    static String clientAddress(HttpServletRequest request, List<AddressRange> trustedProxies) {
        String address = request.getRemoteAddr();
        if (!trusted(address, trustedProxies)) {
            return address;
        }
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            for (String hop : header.split(",")) {
                hops.add(hop.trim());
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = withoutPort(hops.get(i));
            if (AddressRange.literal(hop) == null) {
                return address;
            }
            address = hop;
            if (!trusted(hop, trustedProxies)) {
                return hop;
            }
        }
        return address;
    }

    private static boolean trusted(String address, List<AddressRange> trustedProxies) {
        return address != null && trustedProxies.stream().anyMatch(range -> range.matches(address));
    }

    /** {@code 192.0.2.1:4711} and {@code [2001:db8::1]:4711} as the addresses alone. */
    private static String withoutPort(String hop) {
        if (hop.startsWith("[")) {
            int end = hop.indexOf(']');
            return end < 0 ? hop : hop.substring(1, end);
        }
        int colon = hop.indexOf(':');
        return colon > 0 && colon == hop.lastIndexOf(':') ? hop.substring(0, colon) : hop;
    }

    static final class WithoutHostHeaders extends HttpServletRequestWrapper {

        private final String clientAddress;

        WithoutHostHeaders(HttpServletRequest request, String clientAddress) {
            super(request);
            this.clientAddress = clientAddress;
        }

        @Override
        public String getRemoteAddr() {
            return clientAddress;
        }

        @Override
        public String getRemoteHost() {
            return clientAddress;
        }

        private static boolean ignored(String name) {
            return name != null && IGNORED.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return ignored(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return ignored(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(WithoutHostHeaders::ignored);
            return Collections.enumeration(names);
        }
    }
}
