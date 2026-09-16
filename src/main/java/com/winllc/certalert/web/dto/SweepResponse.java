package com.winllc.certalert.web.dto;

import com.winllc.certalert.service.SweepResult;

/** Outcome of an on-demand sweep. */
public record SweepResponse(int checked, int unhealthy, int errored, long durationMillis) {

    public static SweepResponse from(SweepResult result) {
        return new SweepResponse(
                result.checked(), result.unhealthy(), result.errored(), result.duration().toMillis());
    }
}
