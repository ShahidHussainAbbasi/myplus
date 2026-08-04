-- Slice 3.1 — guardian portal access. The FIRST external login this platform grants.
-- Design: microservices/docs/slices/edu-3.1-guardian-portal.md
--
-- ── One table, and note what is NOT in it: the children ────────────────────────────────────────────
-- "My children" is DERIVED on every request from student.guardian_id (design D1). Storing a child list
-- here would create a second source of truth that goes stale the moment a child transfers, a guardian link
-- is corrected, or a sibling enrols. A stale copy of an ACCESS list is not a caching bug — it is a stranger
-- reading a child's record. There is therefore no guardian_children table and no denormalised column.
--
-- ── Why access is a separate table at all ──────────────────────────────────────────────────────────
-- The guardian record already exists. This row records the school's DECISION to grant a login, which is a
-- different fact with a different lifecycle: a guardian may exist for years without portal access, and
-- revoking access must not touch the guardian record.
--
-- ── Invitation only (D3) ───────────────────────────────────────────────────────────────────────────
-- There is no SELF_REGISTERED status. Self-service registration against a child's enrolment number is an
-- account-takeover path; the school already knows who the guardians are.
--
-- REVOKED rows are KEPT, never deleted: "this person used to have access to this child's record" is
-- precisely what an investigation needs (same append-only reasoning as 1.5 D5 and 2.5 D3).
--
-- ── email is snapshotted, deliberately ─────────────────────────────────────────────────────────────
-- Copied from guardian.email at invitation. If the guardian record's email is later corrected, this access
-- must NOT silently begin authorising a different address — re-inviting is the explicit act that moves it.
-- KNOWN GAP (tracked in the programme's carried requirements): guardian.email is unverified free text, so a
-- typo invites a stranger. Email verification is required before this ships to a real school.
--
-- status is a MySQL enum against @Enumerated(STRING): a new value later needs ALTER ... MODIFY.
-- Indexes follow standard D3b — index the query, not just the scope.
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS guardian_portal_access (
    guardian_portal_access_id BIGINT       NOT NULL AUTO_INCREMENT,
    guardian_id               BIGINT       NOT NULL,
    -- the login identity, snapshotted at invitation (see the header)
    email                     VARCHAR(255) NOT NULL,
    guardian_name             VARCHAR(255) NULL,
    status                    ENUM('INVITED','ACTIVE','REVOKED') NOT NULL DEFAULT 'INVITED',
    invited_on                DATE         NULL,
    activated_on              DATE         NULL,
    revoked_on                DATE         NULL,
    user_id                   BIGINT       NOT NULL,
    organization_id           BIGINT       NULL,
    dated                     DATETIME     NULL,
    updated                   DATETIME     NULL,
    PRIMARY KEY (guardian_portal_access_id),
    UNIQUE KEY uk_portal_access_guardian (organization_id, guardian_id),
    -- THE authentication lookup: "who is this signed-in email, and may they still use the portal?"
    -- Every portal request runs it, so it is the one index that must exist.
    KEY idx_portal_access_email (organization_id, email, status),
    -- the school's admin list of who has been given access
    KEY idx_portal_access_status (organization_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
