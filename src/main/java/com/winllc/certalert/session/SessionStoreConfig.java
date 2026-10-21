package com.winllc.certalert.session;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.EnumSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * Signed-in sessions in the database ({@code spring_session}, V23), when
 * {@code cert-alert.session.store} is {@code jdbc} - the postgres profile's default, and
 * what the chart and the release bundle run.
 *
 * <p>A session then outlives the replica that created it: a rolling update, a restart or a
 * second replica behind the load balancer does not sign anybody out. With the default,
 * {@code memory}, sessions live in the servlet container as they always have, which is what
 * the development profile and the tests use.
 *
 * <p>The cookie keeps the container's settings - HttpOnly, SameSite=Lax, Secure on a TLS
 * request or always with {@code ENS_COOKIE_SECURE} - and its name, {@code SESSION}, differs
 * from the container's {@code JSESSIONID}, so the first request after upgrading to it asks
 * people to sign in once more.
 *
 * <p>Expired sessions are cleared by {@link SessionSweeper} rather than by Spring Session's
 * own timer, so the ones that ended by going idle are recorded as they are with the
 * in-memory store, and only one replica does it.
 */
@Configuration
@ConditionalOnProperty(prefix = "cert-alert.session", name = "store", havingValue = "jdbc")
@EnableJdbcHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
public class SessionStoreConfig {

    @Bean
    public SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionTimeout(
            @Value("${server.servlet.session.timeout:15m}") Duration timeout) {
        return repository -> repository.setDefaultMaxInactiveInterval(timeout);
    }

    /**
     * How attributes are stored and read back: Java serialization, read through an allow-list
     * (see {@link SessionAttributeSerialization}). Spring Session looks for this by name.
     */
    @Bean("springSessionConversionService")
    public org.springframework.core.convert.ConversionService springSessionConversionService(
            org.springframework.context.ApplicationContext context) {
        org.springframework.core.convert.support.GenericConversionService conversions =
                new org.springframework.core.convert.support.GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, SessionAttributeSerialization.serializer());
        conversions.addConverter(byte[].class, Object.class,
                SessionAttributeSerialization.deserializer(context.getClassLoader()));
        return conversions;
    }

    @Bean
    public CookieSerializer sessionCookie(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        cookie.setCookieName("SESSION");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        if (secure) {
            cookie.setUseSecureCookie(true);
        }
        return cookie;
    }

    /**
     * On every dispatch a session may be read in - an error page included - and ahead of
     * the security filters, as Spring Boot registers it itself.
     */
    @Bean
    public FilterRegistrationBean<SessionRepositoryFilter<?>> sessionRepositoryFilterRegistration(
            SessionRepositoryFilter<?> filter) {
        FilterRegistrationBean<SessionRepositoryFilter<?>> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
        return registration;
    }
}
