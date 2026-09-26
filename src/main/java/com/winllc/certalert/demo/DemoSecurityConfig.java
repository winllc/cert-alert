package com.winllc.certalert.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo's idea of security: everybody is welcome, nobody may change anything.
 *
 * <p>Replaces the ordinary configuration rather than relaxing it, so the two policies are
 * never half-applied. There is no sign-in page, because there is nothing to sign in to;
 * every visitor arrives as the same administrator, which is what makes the administration
 * pages part of what is being shown.
 *
 * <p><strong>Read-only is a rule about the request, not about the buttons.</strong> A
 * demo whose safety depended on the UI not offering something would be one page of
 * somebody's curiosity away from a directory being resynced. So anything that is not a
 * plain read is refused here, before any controller sees it, and the buttons that provoke
 * it stay exactly where they are.
 *
 * <p>The exception is the three endpoints the search tables read through. They are POSTs
 * because DataTables sends its paging, ordering and filters as a body, and they write
 * nothing; without them the demo has no tables.
 */
@Configuration
@EnableWebSecurity
@Conditional(DemoMode.On.class)
public class DemoSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoSecurityConfig.class);

    /** Reads that arrive as POSTs, because that is how DataTables asks. */
    private static final String[] READ_ONLY_POSTS = {
        "/api/v1/datatables/users", "/api/v1/datatables/servers", "/api/v1/datatables/audit"
    };

    /**
     * What a visitor is told when they press something.
     *
     * <p>Said in the response rather than only in the page's script, so that pressing a
     * button and calling the endpoint directly get the same answer. A bare 403 reads like
     * something is broken; this reads like the demo working as intended.
     */
    public static final String REFUSAL = "This is a read-only demo: nothing here can be changed.";

    @Bean
    public SecurityFilterChain demoFilterChain(HttpSecurity http, DemoProperties demo, ObjectMapper json)
            throws Exception {
        log.warn("DEMO MODE: no authentication is required and every visitor is an administrator. "
                + "Nothing can be changed, but everything in this directory can be read by anyone "
                + "who can reach this application.");

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, READ_ONLY_POSTS)
                        .permitAll()
                        .anyRequest()
                        .access((authentication, context) ->
                                new AuthorizationDecision(isRead(context.getRequest()))))
                // Nobody signs in, so there is no session to fix and no form to post to.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // Every visitor is the same person, signed in before anything is decided
                // about them - see the filter for why this is not Spring's anonymous
                // authentication, which looks like the obvious way to do it.
                .addFilterBefore(new DemoVisitorFilter(demo), AuthorizationFilter.class)
                // A denial from the authorization filter never reaches the controller
                // advice - it is translated further down the chain - so the wording has to
                // be put on the response here.
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(refuse(json)));

        return http.build();
    }

    /**
     * Answers a refused write with the reason, as problem detail.
     *
     * <p>Every refusal in the demo is a script's: the pages offer the same buttons they
     * always do and it is their fetch that comes back 403. So there is one shape of answer
     * rather than a page and a body, and the script shows what it is given.
     */
    private static AccessDeniedHandler refuse(ObjectMapper json) {
        return (request, response, denied) -> {
            if (response.isCommitted()) {
                return;
            }
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, REFUSAL);
            problem.setTitle("Read-only demo");
            problem.setInstance(URI.create(request.getRequestURI()));

            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            json.writeValue(response.getWriter(), problem);
        };
    }

    /**
     * Whether this request only looks.
     *
     * <p>By method rather than by path: a list of everything that writes is a list somebody
     * has to remember to add to, and the one that gets forgotten is the one that matters.
     * The safe methods are the ones HTTP already says are safe.
     */
    private static boolean isRead(HttpServletRequest request) {
        String method = request.getMethod();
        return HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method);
    }

}
