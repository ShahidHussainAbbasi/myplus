package com.myplus.education.entity;

/**
 * Slice 2.5 — what kind of note this is (design D2).
 *
 * <p><b>{@code POSITIVE} is not decoration.</b> A behaviour log that can only record problems is a
 * punishment ledger, and teachers learn not to open it. The same screen recording "helped a new student
 * settle in" is one people actually use — which is what makes the {@code CONCERN} entries credible when
 * they do appear. One enum value, and it changes what the feature is for.
 *
 * <p>{@code NEUTRAL} exists for a factual note that is neither praise nor complaint ("left early for a
 * dental appointment"), so people are not forced to mis-classify in order to record something true.
 *
 * <p>Persisted with {@code @Enumerated(STRING)} against a MySQL enum column: adding a value later needs an
 * explicit {@code ALTER … MODIFY}, because ddl-auto will not do it and fails with "Data truncated".
 */
public enum BehaviourType {
    POSITIVE,
    CONCERN,
    NEUTRAL
}
