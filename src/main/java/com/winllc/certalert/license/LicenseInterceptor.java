package com.winllc.certalert.license;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Refuses the addresses of a licensed feature the license does not cover.
 *
 * <p>Throws rather than writing the response itself, so the refusal goes through
 * {@code GlobalExceptionHandler} and comes out the way every other refusal does: problem
 * detail for {@code /api}, a page for a browser. Runs after the filter chain, so somebody
 * who is not signed in is still asked to sign in first rather than told about licensing.
 */
public class LicenseInterceptor implements HandlerInterceptor {

    private record Rule(PathPattern pattern, LicensedFeature feature) {}

    private final LicenseService licenseService;
    private final List<Rule> rules = new ArrayList<>();

    public LicenseInterceptor(LicenseService licenseService) {
        this.licenseService = licenseService;
        PathPatternParser parser = PathPatternParser.defaultInstance;
        for (LicensedFeature feature : LicensedFeature.values()) {
            for (String path : feature.paths()) {
                rules.add(new Rule(parser.parse(path), feature));
            }
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer container = PathContainer.parsePath(path);
        for (Rule rule : rules) {
            if (rule.pattern().matches(container) && !licenseService.isEnabled(rule.feature().id())) {
                throw new LicenseRequiredException(rule.feature());
            }
        }
        return true;
    }
}
