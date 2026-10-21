package com.winllc.certalert.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * {@code X-Forwarded-Proto}, {@code -Port} and {@code -For}: what is needed to see that the
 * browser's request was https, and from where. Nothing that matters to a sign-in is built
 * from the request's host in any case - single sign-on's addresses come from
 * {@code cert-alert.sso.public-url} - but there is no reason to let a client move it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
public class ForwardedHeadersConfig {

    /** Never believed from a request, whoever sent it. */
    static final Set<String> IGNORED = Set.of("forwarded", "x-forwarded-host", "x-forwarded-prefix");

    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> hostPinningForwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new HostPinningForwardedHeaderFilter());
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Spring's filter, applied to the request with the host-moving headers taken out. */
    public static final class HostPinningForwardedHeaderFilter extends ForwardedHeaderFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            super.doFilterInternal(new WithoutHostHeaders(request), response, chain);
        }
    }

    static final class WithoutHostHeaders extends HttpServletRequestWrapper {

        WithoutHostHeaders(HttpServletRequest request) {
            super(request);
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
