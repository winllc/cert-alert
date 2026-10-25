package com.winllc.certalert.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * Where a sign-in lands - by password, OpenID Connect or SAML: back on the page that sent
 * somebody to the sign-in page, as Spring's own handler does - unless the person who has
 * just signed in may not read it, in which case they start at {@code /} like anybody who
 * came to the sign-in page directly.
 *
 * <p>The page that was asked for need not be theirs. A session that ends on an
 * administration page leaves that page saved for whoever signs in next, and a demo invites
 * signing in as one account after another; following the saved page regardless greeted
 * them with a 403. Whether they may read it is asked of the filter chain's own rules, so
 * the answer is the one the page itself would give.
 *
 * <p>The privilege evaluator is looked up when somebody signs in, not when the chain is
 * built: it is made from the filter chains, this handler among them.
 */
public final class SignInSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final ObjectProvider<WebInvocationPrivilegeEvaluator> privileges;

    public SignInSuccessHandler(ObjectProvider<WebInvocationPrivilegeEvaluator> privileges) {
        this.privileges = privileges;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws ServletException, IOException {
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved instanceof DefaultSavedRequest page && !mayRead(page, authentication)) {
            requestCache.removeRequest(request, response);
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    private boolean mayRead(DefaultSavedRequest page, Authentication authentication) {
        WebInvocationPrivilegeEvaluator evaluator = privileges.getIfUnique();
        if (evaluator == null) {
            return true;
        }
        String contextPath = page.getContextPath();
        String path = page.getRequestURI().substring(contextPath.length());
        return evaluator.isAllowed(contextPath, path, page.getMethod(), authentication);
    }
}
