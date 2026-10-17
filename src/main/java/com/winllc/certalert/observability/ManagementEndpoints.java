package com.winllc.certalert.observability;

import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * {@code /actuator/prometheus} only on the management port.
 *
 * <p>The ENS baseline keeps metrics off the port people use: they are served only when
 * {@code MANAGEMENT_SERVER_PORT} gives the actuator a port of its own - which the ingress or
 * route never publishes, and where health and metrics are open without signing in. With no
 * management port set, which is the default, Prometheus is not exposed at all, and the main
 * port's actuator is exactly what it was.
 */
@Configuration(proxyBeanMethods = false)
public class ManagementEndpoints {

    private static final EndpointId PROMETHEUS = EndpointId.of("prometheus");

    @Bean
    public EndpointFilter<ExposableWebEndpoint> prometheusOnTheManagementPortOnly(Environment environment) {
        boolean ownPort = hasOwnPort(environment);
        return endpoint -> ownPort || !PROMETHEUS.equals(endpoint.getEndpointId());
    }

    /**
     * The scrape, open without signing in - which can only be on the management port, since
     * that is the only place it is served. Matches nothing without one.
     */
    public static RequestMatcher prometheusScrape(Environment environment) {
        RequestMatcher path = PathPatternRequestMatcher.withDefaults().matcher("/actuator/prometheus");
        boolean ownPort = hasOwnPort(environment);
        return request -> ownPort && path.matches(request);
    }

    private static boolean hasOwnPort(Environment environment) {
        return ManagementPortType.get(environment) == ManagementPortType.DIFFERENT;
    }
}
