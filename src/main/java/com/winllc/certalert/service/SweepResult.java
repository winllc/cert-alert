package com.winllc.certalert.service;

import java.time.Duration;

/**
 * Summary of one sweep over all enabled targets.
 *
 * @param checked number of targets inspected
 * @param unhealthy number of targets that came back in an alertable state
 * @param errored number of targets whose check threw an unexpected error
 * @param duration wall-clock time taken by the sweep
 */
public record SweepResult(int checked, int unhealthy, int errored, Duration duration) {}
