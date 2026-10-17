package com.winllc.certalert.audit;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Walks the retained trail in seq order and recomputes every hash.
 *
 * <p>The first retained event is the anchor. After pruning it is not seq 1, and its
 * prevHash names an event that is no longer there, so it is taken as given - and the
 * result says so. From there on each event must follow the one before it without a gap,
 * state the previous event's hash as its prevHash, and hash to what it says it hashes to.
 * Editing a stored event breaks it at that seq; deleting one breaks the chain at the next.
 */
@Component
public class AuditVerifier {

    private static final int PAGE = 1000;
    private static final Pattern SEQ = Pattern.compile("^\\{\"schema\":\"[^\"]*\",\"id\":\"[^\"]*\",\"seq\":(\\d+),");

    private final AuditStore store;

    public AuditVerifier(AuditStore store) {
        this.store = store;
    }

    /**
     * @param intact whether every retained event checked out
     * @param checked how many were checked
     * @param anchorSeq the first retained seq, whose prevHash was accepted as given; null when empty
     * @param anchorPrevHash that prevHash
     * @param lastSeq the last seq checked
     * @param brokenSeq the first seq that did not check out, or null
     * @param problem what was wrong with it
     */
    public record Result(
            boolean intact,
            long checked,
            Long anchorSeq,
            String anchorPrevHash,
            Long lastSeq,
            Long brokenSeq,
            String problem) {

        public String summary() {
            if (anchorSeq == null) {
                return "The audit trail is empty.";
            }
            String anchor = anchorSeq == 1
                    ? "from seq 1"
                    : "anchored at seq " + anchorSeq + " (earlier events were pruned; its prevHash "
                            + anchorPrevHash.substring(0, 12) + "... is accepted as given)";
            if (intact) {
                return "Intact: " + checked + " event(s) verified " + anchor + " to seq " + lastSeq + ".";
            }
            return "Broken at seq " + brokenSeq + ": " + problem + ". Checked " + anchor + ".";
        }
    }

    public Result verify() {
        long checked = 0;
        Long anchor = null;
        String anchorPrev = null;
        String previousHash = null;
        long previousSeq = 0;
        long cursor = 0;
        while (true) {
            List<StoredEvent> page = store.after(cursor, PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (StoredEvent event : page) {
                String problem = check(event, anchor == null ? null : previousHash, previousSeq);
                if (anchor == null) {
                    anchor = event.seq();
                    anchorPrev = event.line() == null || event.line().length() < 75
                            ? "" : AuditFormat.prevHashOf(AuditFormat.bodyOf(event.line()));
                }
                if (problem != null) {
                    return new Result(false, checked, anchor, anchorPrev, previousSeq == 0 ? null : previousSeq,
                            event.seq(), problem);
                }
                checked++;
                previousHash = AuditFormat.hashOf(event.line());
                previousSeq = event.seq();
            }
            cursor = page.getLast().seq();
        }
        return new Result(true, checked, anchor, anchorPrev, anchor == null ? null : previousSeq, null, null);
    }

    /** What is wrong with this event, or null. */
    private static String check(StoredEvent event, String previousHash, long previousSeq) {
        String line = event.line();
        if (line == null || line.length() < 75 || !line.endsWith("\"}")) {
            return "the stored line is missing or malformed";
        }
        if (previousHash != null && event.seq() != previousSeq + 1) {
            return "seq " + (previousSeq + 1) + " is missing";
        }
        Matcher seq = SEQ.matcher(line);
        if (!seq.find() || Long.parseLong(seq.group(1)) != event.seq()) {
            return "the line's seq does not match its position";
        }
        String body = AuditFormat.bodyOf(line);
        String stated = AuditFormat.hashOf(line);
        String prevHash = AuditFormat.prevHashOf(body);
        if (previousHash != null && !prevHash.equals(previousHash)) {
            return "its prevHash is not the hash of seq " + previousSeq;
        }
        if (!AuditFormat.hash(prevHash, body).equals(stated)) {
            return "its hash does not match its content";
        }
        return null;
    }
}
