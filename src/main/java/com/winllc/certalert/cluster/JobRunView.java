package com.winllc.certalert.cluster;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A run started by hand, as {@code /api/v1/jobs/{id}} reports it.
 *
 * @param result what the job returned - the same object the request used to answer with
 *     before jobs ran in the background - once it has succeeded
 * @param error why it failed, or why it was skipped
 * @param statusUrl where to ask after it
 */
public record JobRunView(
        String id,
        String job,
        JobRun.State state,
        boolean finished,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        JsonNode result,
        String error,
        String statusUrl) {

    static JobRunView of(JobRun run, ObjectMapper json) {
        JsonNode result = run.getResult() == null ? null : json.readTree(run.getResult());
        return new JobRunView(
                run.getId(),
                run.getJob(),
                run.getState(),
                run.getState().finished(),
                run.getRequestedAt(),
                run.getStartedAt(),
                run.getFinishedAt(),
                result,
                run.getError(),
                JobRunController.PATH + "/" + run.getId());
    }
}
