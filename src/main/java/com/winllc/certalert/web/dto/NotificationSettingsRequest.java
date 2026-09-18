package com.winllc.certalert.web.dto;

/**
 * Setting how many days before expiry the round-up writes to people.
 *
 * <p>The bounds are the service's, not an annotation's: it is the same rule whether the
 * number arrives from this page or from anywhere else, and rejecting it there is what
 * produces a message saying what the bounds are rather than "request validation failed".
 */
public record NotificationSettingsRequest(int leadDays) {}
