package com.myplus.education.entity;

/**
 * Slice 3.3 — who a {@link GuardianPortalAccess} row is about.
 *
 * <p>Two external audiences share one access table, one invite/revoke flow and one deny rule; what differs
 * is whose record the login opens. That is a column, not a second table — see V25's header for why.
 *
 * <p><b>@Enumerated(STRING) against a MySQL enum:</b> adding a value here needs an
 * {@code ALTER … MODIFY} migration, or the insert fails with "Data truncated". Recorded because this
 * platform has been caught by it before.
 */
public enum PortalSubjectType {

    /** An adult who may see SEVERAL children — the set is derived from {@code Student.guardianId} (3.1). */
    GUARDIAN,

    /**
     * A student seeing their OWN record, and exactly one.
     *
     * <p>Because that set has one member, the student endpoints accept no enrolment number at all rather
     * than validating one — there is nothing to choose between, so a parameter would only ever be an IDOR
     * surface with no purpose (3.3 D2).
     */
    STUDENT
}
