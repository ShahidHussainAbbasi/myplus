-- E5 — the SUPPORT SESSION.
--
-- Until now a platform operator reached any tenant because CurrentUser.organizationIdFor asked one question:
-- "are you ROLE_ADMIN?" A yes handed over every customer, for any reason, for ever. That was correct when
-- ONB-3 introduced it -- the alternative was an operator preview showing the operator's own figures under the
-- customer's name -- but it is a standing grant, and a standing grant cannot expire, cannot be explained, and
-- cannot be shown to the person it is about.
--
-- This table is what the question is asked of instead.

CREATE TABLE support_session (
    id                BIGINT       NOT NULL AUTO_INCREMENT,

    operator_user_id  BIGINT       NOT NULL,
    -- Stamped, not resolved on read: the record has to stay readable after the staff member has left and
    -- their user row is gone. Same rule as audit_event.actor_email and CustomerHistory.bookedByName.
    operator_email    VARCHAR(160) NULL,

    subject_org_id    BIGINT       NOT NULL,

    -- Required by the API, not merely by the form. An unexplained look at a customer's books is exactly what
    -- this slice exists to prevent, and the endpoint is reachable without the screen.
    reason            VARCHAR(255) NOT NULL,

    -- D-2: a read notifies the tenant, a WRITE needs their approval. Default 0 -- a session that could write
    -- the moment it opened would make the consent a formality.
    write_approved    TINYINT(1)   NOT NULL DEFAULT 0,
    approved_by       BIGINT       NULL,
    approved_at       DATETIME     NULL,

    opened_at         DATETIME     NOT NULL,
    expires_at        DATETIME     NOT NULL,
    closed_at         DATETIME     NULL,
    closed_by         BIGINT       NULL,

    PRIMARY KEY (id),
    KEY idx_support_open (operator_user_id, subject_org_id, expires_at),
    KEY idx_support_subject (subject_org_id, id)
);

-- ⚠ THERE IS DELIBERATELY NO `status` COLUMN.
--
-- Open-ness is `closed_at IS NULL AND expires_at > NOW()` -- derived, so it cannot drift from the clock. A
-- status column would need a job to expire it, and a job that does not run is a session that never ends: the
-- one failure this table exists to make impossible. The same reasoning as audit_event storing before/after
-- rather than a "changed" flag.
