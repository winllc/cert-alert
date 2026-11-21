package com.winllc.certalert.audit;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Walks the retained trail in seq order and recomputes every hash.
 *
 * <p>The first retained event is the anchor: seq 1, chained to 64 zeros, or, after pruning,
 * the event right after the last one retention deleted, chained to that event's hash (both
 * kept on the chain head; an installation that pruned before the head kept the hash has its
 * anchor's prevHash taken as given). From there on each event must follow the one before it
 * without a gap, state the previous event's hash as its prevHash, and hash to what it says it
 * hashes to, and the walk must reach the chain head's seq and hash. Editing a stored event
 * breaks it at that seq; deleting one breaks the chain at the next, or at the head.
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
     * @param anchorSeq the first retained seq; null when empty
     * @param anchorPrevHash its prevHash
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
            if (anchorSeq == null && intact) {
                return "The audit trail is empty.";
            }
            String anchor = anchorSeq == null
                    ? "with no event retained"
                    : anchorSeq == 1
                    ? "from seq 1"
                    : "anchored at seq " + anchorSeq + " (retention pruned the events before it; its prevHash "
                            + anchorPrevHash.substring(0, Math.min(12, anchorPrevHash.length())) + "...)";
            if (intact) {
                return "Intact: " + checked + " event(s) verified " + anchor + " to seq " + lastSeq + ".";
            }
            return "Broken at seq " + brokenSeq + ": " + problem + ". Checked " + anchor + ".";
        }
    }

    public Result verify() {
        AuditStore.ChainState head = store.chainState();
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
                    if (problem == null) {
                        Result misplaced = anchorProblem(head, event.seq(), anchorPrev);
                        if (misplaced != null) {
                            // retention may have pruned meanwhile: judge by the head as it is now
                            misplaced = anchorProblem(store.chainState(), event.seq(), anchorPrev);
                        }
                        if (misplaced != null) {
                            return misplaced;
                        }
                    }
                }
                if (problem == null && event.seq() == head.lastSeq()
                        && !AuditFormat.hashOf(event.line()).equals(head.lastHash())) {
                    problem = "its hash is not the chain head's";
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
        if (previousSeq < head.lastSeq()) {
            long missing = Math.max(previousSeq, head.prunedThroughSeq()) + 1;
            return new Result(false, checked, anchor, anchorPrev, previousSeq == 0 ? null : previousSeq, missing,
                    "seq " + missing + " to " + head.lastSeq() + " are missing (the chain head is at seq "
                            + head.lastSeq() + ")");
        }
        return new Result(true, checked, anchor, anchorPrev, anchor == null ? null : previousSeq, null, null);
    }

    /** Why the oldest retained event is not where the chain starts or retention's pruning left it, or null. */
    private static Result anchorProblem(AuditStore.ChainState head, long seq, String prevHash) {
        if (seq == 1) {
            return AuditFormat.GENESIS.equals(prevHash) ? null
                    : new Result(false, 0, seq, prevHash, null, seq, "the first event's prevHash is not 64 zeros");
        }
        long prunedThrough = head.prunedThroughSeq();
        if (prunedThrough >= seq) {
            return new Result(false, 0, seq, prevHash, null, seq,
                    "retention pruned up to seq " + prunedThrough + ", yet this event is there");
        }
        if (prunedThrough != seq - 1) {
            return new Result(false, 0, seq, prevHash, null, prunedThrough + 1, "seq " + (prunedThrough + 1)
                    + " to " + (seq - 1) + " are missing; retention " + (prunedThrough == 0 ? "has pruned nothing"
                    : "pruned only up to seq " + prunedThrough));
        }
        if (head.prunedThroughHash() != null && !head.prunedThroughHash().equals(prevHash)) {
            return new Result(false, 0, seq, prevHash, null, seq,
                    "its prevHash is not the hash of seq " + prunedThrough + ", the last event retention pruned");
        }
        return null;
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
