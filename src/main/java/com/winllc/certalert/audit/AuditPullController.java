package com.winllc.certalert.audit;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SIEM pull API: {@code GET /api/audit/events?after=<seq>&limit=<1..1000>}.
 *
 * <p>Newline-delimited JSON by default - one stored line per event, ascending seq, exactly
 * as the chain holds it - or, with {@code Accept: application/json},
 * {@code {"events":[...],"next":<seq>}}, where {@code next} is the {@code after} to ask with
 * next time. Authenticated by an audit API token ({@link AuditApiSecurityConfig}). Not itself
 * audited: the token's last use is recorded instead.
 */
@RestController
public class AuditPullController {

    public static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson;charset=UTF-8");

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 1000;

    private final AuditStore store;

    public AuditPullController(AuditStore store) {
        this.store = store;
    }

    @GetMapping("/api/audit/events")
    public ResponseEntity<String> events(
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestHeader(name = "Accept", required = false) String accept) {
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);
        List<StoredEvent> events = store.after(Math.max(0, after), bounded);
        long next = events.isEmpty() ? Math.max(0, after) : events.getLast().seq();
        boolean json = accept != null && accept.contains("application/json") && !accept.contains("ndjson");
        if (json) {
            StringBuilder body = new StringBuilder("{\"events\":[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) {
                    body.append(',');
                }
                body.append(events.get(i).line());
            }
            body.append("],\"next\":").append(next).append('}');
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body.toString());
        }
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
            body.append(event.line()).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .header("X-Audit-Next", Long.toString(next))
                .body(body.toString());
    }
}
