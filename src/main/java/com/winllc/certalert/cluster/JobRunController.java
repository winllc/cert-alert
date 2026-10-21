package com.winllc.certalert.cluster;

import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * How the jobs started by hand are going (see {@link JobRuns}). Administrators - who start
 * them - and auditors only (SecurityConfig): a result can name the people a round-up wrote to,
 * and an error can carry the mail server's or the directory's own words.
 */
@RestController
@RequestMapping(JobRunController.PATH)
public class JobRunController {

    public static final String PATH = "/api/v1/jobs";

    private final JobRuns runs;

    public JobRunController(JobRuns runs) {
        this.runs = runs;
    }

    @GetMapping("/{id}")
    public JobRunView run(@PathVariable String id) {
        return runs.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such run"));
    }

    @GetMapping
    public List<JobRunView> recent(@RequestParam(defaultValue = "20") int limit) {
        return runs.recent(limit);
    }

    /**
     * The answer to a request that started a run: 202, with where to ask after it in
     * {@code Location} and in the body.
     */
    public static ResponseEntity<JobRunView> accepted(JobRuns runs, String job, Function<JobRuns.Step, ?> body) {
        JobRunView run = runs.submit(job, body);
        return ResponseEntity.accepted().location(URI.create(run.statusUrl())).body(run);
    }
}
