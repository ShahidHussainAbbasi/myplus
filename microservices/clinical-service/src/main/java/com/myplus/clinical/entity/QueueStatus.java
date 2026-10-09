package com.myplus.clinical.entity;

import java.util.List;
import java.util.Map;

/**
 * HMS S2 — the token's states and the ONLY moves between them (design §2, state diagram). Strings, not an enum,
 * because the column is VARCHAR and is read back by a native generated column.
 *
 * <pre>
 *   WAITING → CALLED → IN_CONSULTATION ⇄ PARKED → COMPLETED
 *   CALLED  → WAITING (recall: the patient was not there)
 *   WAITING | CALLED → CANCELLED | NO_SHOW
 * </pre>
 */
public final class QueueStatus {

    private QueueStatus() {}

    public static final String WAITING = "WAITING";
    public static final String CALLED = "CALLED";
    public static final String IN_CONSULTATION = "IN_CONSULTATION";
    public static final String PARKED = "PARKED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String NO_SHOW = "NO_SHOW";

    /** Live = still in today's line (anything but the three end states). */
    public static final List<String> LIVE = List.of(WAITING, CALLED, IN_CONSULTATION, PARKED);

    /** Being seen right now — a patient may be in at most one of these, across all doctors (04c). */
    public static final List<String> WITH_DOCTOR = List.of(CALLED, IN_CONSULTATION);

    /** action → (the states it may start from, the state it ends in). */
    public static final Map<String, Move> MOVES = Map.of(
            "call", new Move(List.of(WAITING), CALLED),
            "recall", new Move(List.of(CALLED), WAITING),
            "start", new Move(List.of(CALLED), IN_CONSULTATION),
            "park", new Move(List.of(IN_CONSULTATION), PARKED),
            "resume", new Move(List.of(PARKED), IN_CONSULTATION),
            "complete", new Move(List.of(IN_CONSULTATION), COMPLETED),
            "cancel", new Move(List.of(WAITING, CALLED), CANCELLED),
            "noShow", new Move(List.of(WAITING, CALLED), NO_SHOW));

    public record Move(List<String> from, String to) {}

    /** For refusals a person can read: "COMPLETED" → "completed". */
    public static String words(String status) {
        return status == null ? "" : status.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
