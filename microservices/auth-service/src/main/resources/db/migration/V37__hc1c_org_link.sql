-- HC-1c (2026-10-15) — a Hospital / Clinic LINKED to a pharmacy (two separate tenants). The first relationship in the
-- platform that crosses tenants. Design: microservices/docs/slices/hc-1cd-clinic-pharmacy-link.md.
--
-- One row per request: REQUESTED (the clinic asked) -> ACTIVE (the pharmacy accepted) -> ENDED (either side, with a
-- reason). A row is never deleted and never re-opened: ending and asking again is a new row, so the history of who
-- linked whom, and when, stays readable.
-- One REQUESTED-or-ACTIVE row per clinic (the user, 2026-10-15) is enforced in OrgLinkService under a row lock on the
-- clinic's organisation — MySQL has no partial unique index.
--
-- InnoDB stated (D15); VARCHAR for every String field (ddl-auto=validate); idempotent (D7).
-- No placeholder syntax anywhere in this file (Flyway would substitute it, even in a comment).

CREATE TABLE IF NOT EXISTS org_link (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    clinic_org_id    BIGINT       NOT NULL,
    pharmacy_org_id  BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    requested_by     BIGINT       NULL,
    requested_at     DATETIME(6)  NOT NULL,
    accepted_by      BIGINT       NULL,
    accepted_at      DATETIME(6)  NULL,
    ended_by         BIGINT       NULL,
    ended_at         DATETIME(6)  NULL,
    ended_by_org_id  BIGINT       NULL,
    end_reason       VARCHAR(255) NULL,
    PRIMARY KEY (id),
    KEY idx_org_link_clinic (clinic_org_id, status),
    KEY idx_org_link_pharmacy (pharmacy_org_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
