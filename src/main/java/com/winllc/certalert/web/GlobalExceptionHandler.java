package com.winllc.certalert.web;

import com.winllc.certalert.service.AlreadyExistsException;
import com.winllc.certalert.service.ProbeUnavailableException;
import com.winllc.certalert.service.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * One place where every exception that escapes a controller is turned into an answer.
 *
 * <p>The same failure has two audiences. A script calling {@code /api/v1} wants RFC 7807
 * problem detail it can branch on; somebody who followed a link wants a page that says
 * what happened and offers a way onward. So the exception decides the status and the
 * wording, and the request decides which of the two it is rendered as - rather than the
 * advice picking one and being wrong for half its callers.
 *
 * <p>Deliberately not a {@code ResponseEntityExceptionHandler}: that base class answers
 * Spring's own exceptions with problem detail whatever was asked for, which is how a
 * mistyped address came to serve a browser JSON. Their statuses are not lost - every one
 * of them implements {@link ErrorResponse} and carries its own, which
 * {@link #handleUnexpected} reads rather than calling everything a 500.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final CurrentUserAdvice currentUser;
    private final Clock clock;

    public GlobalExceptionHandler(CurrentUserAdvice currentUser, Clock clock) {
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public Object handleNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        if (wantsHtml(request)) {
            // A missing entry has a page of its own, because there is something specific to
            // say about it: the directory may simply have stopped publishing it.
            return page("not-found", HttpStatus.NOT_FOUND, e.getMessage(), request);
        }
        return problem(HttpStatus.NOT_FOUND, "Resource not found", e.getMessage());
    }

    /**
     * Something the caller asked to add is already there. The title comes from whoever
     * raised it rather than being fixed here: four different things raise this, and only
     * one of them is a point of contact.
     */
    @ExceptionHandler(AlreadyExistsException.class)
    public Object handleDuplicate(AlreadyExistsException e, HttpServletRequest request) {
        return respond(request, HttpStatus.CONFLICT, e.getTitle(), e.getMessage());
    }

    /**
     * Asked to probe something there is no probing: an entry that says nowhere it lives, or
     * probing switched off here. A refusal about this request rather than a fault, and
     * distinct from an endpoint that answered by being unreachable - which is a result.
     */
    @ExceptionHandler(ProbeUnavailableException.class)
    public Object handleProbeUnavailable(ProbeUnavailableException e, HttpServletRequest request) {
        return respond(request, HttpStatus.CONFLICT, e.getTitle(), e.getMessage());
    }

    /**
     * A request the application rejected on its own terms - an address that is not one, say.
     * The message is the point of it, so unlike the unexpected case it is passed through.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleInvalid(IllegalArgumentException e, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    /**
     * A denial decided by the application rather than by the filter chain - who may manage
     * a particular server's contacts, say. Without this the catch-all below would report it
     * as an internal error, which is both wrong and alarming.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Object handleDenied(AccessDeniedException e, HttpServletRequest request) {
        return respond(request, HttpStatus.FORBIDDEN, "Not allowed", e.getMessage());
    }

    /** A rejected request body, answered with the fields that were wrong. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult()
                .getFieldErrors()
                .forEach(fieldError -> errors.put(fieldError.getField(), fieldError.getDefaultMessage()));

        if (wantsHtml(request)) {
            return page("error", HttpStatus.BAD_REQUEST, null, request);
        }
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Everything with no handler of its own.
     *
     * <p>Spring's own exceptions arrive here too, and they are not faults of ours: an
     * address that matches no route, a body that would not parse, a method the endpoint
     * does not answer to. Each implements {@link ErrorResponse} and knows its own status
     * and a description safe to repeat, so those are used and nothing is logged at error.
     * What is left really is unexpected, and is logged with its stack and reported without
     * one - a stack trace in a browser tells an attacker more than it tells the reader.
     */
    @ExceptionHandler(Exception.class)
    public Object handleUnexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse framework) {
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            log.debug("{} while serving {}: {}", status, request.getRequestURI(), e.getMessage());
            String title = framework.getBody().getTitle();
            return respond(request, status, title != null ? title : status.getReasonPhrase(), null);
        }

        log.error("Unhandled exception while serving {}", request.getRequestURI(), e);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", null);
    }

    /** Problem detail for a caller that parses, a page for a caller that reads. */
    private Object respond(HttpServletRequest request, HttpStatus status, String title, String detail) {
        return wantsHtml(request)
                ? page("error", status, detail, request)
                : problem(status, title, detail);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = detail == null
                ? ProblemDetail.forStatus(status)
                : ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * The same attributes Boot's own error dispatch supplies, so {@code error.html} serves
     * both paths. The signed-in person is added by hand because model attributes from
     * {@code @ControllerAdvice} do not run for an exception handler, and a page missing the
     * account menu looks like a different application.
     */
    private ModelAndView page(String view, HttpStatus status, String message, HttpServletRequest request) {
        ModelAndView modelAndView = new ModelAndView(view);
        modelAndView.setStatus(status);
        modelAndView.addObject("status", status.value());
        modelAndView.addObject("error", status.getReasonPhrase());
        modelAndView.addObject("message", message);
        modelAndView.addObject("path", request.getRequestURI());
        // To the second: the nanoseconds a clock offers are noise in a bug report.
        modelAndView.addObject("timestamp", Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
        modelAndView.addObject("currentUser", currentUser.currentUser());
        modelAndView.addObject("isAdmin", currentUser.isAdmin());
        return modelAndView;
    }

    /**
     * Whether to answer with a page.
     *
     * <p>Decided by what the address serves when nothing goes wrong. Everything under
     * {@code /api} and {@code /actuator} is machine-readable however it was asked for - a
     * browser pointed at an endpoint is still an endpoint, and a page there would break
     * the caller that meant to parse it. Everywhere else only ever renders HTML, so HTML
     * is what a failure there should be, and a caller that wants otherwise has to say so.
     *
     * <p>Said so means naming a machine format and not HTML. Saying nothing at all is not
     * that: {@code curl} sends no {@code Accept} header and means nothing by it.
     */
    private boolean wantsHtml(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/") || path.startsWith("/actuator/")) {
            return false;
        }
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank() || accept.contains("text/html")) {
            return true;
        }
        return !accept.contains("json") && !accept.contains("xml");
    }
}
