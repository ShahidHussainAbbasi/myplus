-- HMS S2 — today's line at the clinic (docs/hms-phase1-design.md §4b). Additive only: V1's tables are untouched.

-- One token = one patient's place in one doctor's line for one day.
--
-- live_patient_id is a STORED generated column: the patient while the token is live, NULL once it is CANCELLED or
-- NO_SHOW. uq_token_live is therefore "one LIVE token per patient per doctor per day" (case 02b) enforced by the
-- database, while a cancelled token can still be re-issued — NULLs never collide in a UNIQUE index.
--
-- Every status change is ONE conditional UPDATE (… WHERE status IN (allowed)); see QueueTokenRepo.transition.
CREATE TABLE queue_token (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    patient_id       BIGINT        NOT NULL,
    provider_id      BIGINT        NOT NULL,
    provider_name    VARCHAR(120)  NULL,
    visit_date       DATE          NOT NULL,
    token_no         INT           NOT NULL,
    token_label      VARCHAR(16)   NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'WAITING',
    live_patient_id  BIGINT        AS (CASE WHEN status IN ('CANCELLED', 'NO_SHOW') THEN NULL ELSE patient_id END) STORED,
    park_reason      VARCHAR(255)  NULL,
    cancel_reason    VARCHAR(255)  NULL,
    called_by        BIGINT        NULL,
    called_at        DATETIME      NULL,
    started_at       DATETIME      NULL,
    parked_at        DATETIME      NULL,
    completed_at     DATETIME      NULL,
    cancelled_at     DATETIME      NULL,
    created_by       BIGINT        NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_token_no (organization_id, provider_id, visit_date, token_no),
    UNIQUE KEY uq_token_live (organization_id, provider_id, visit_date, live_patient_id),
    KEY idx_token_board (organization_id, visit_date, status),
    KEY idx_token_label (organization_id, visit_date, token_label),
    KEY idx_token_patient (organization_id, patient_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The clinic's own facts about a doctor (the doctor itself is appointment-service's provider): the token letter.
-- A for the clinic's first doctor, B for the next — uq_provider_prefix keeps two doctors off one letter.
CREATE TABLE clinic_provider (
    organization_id  BIGINT       NOT NULL,
    provider_id      BIGINT       NOT NULL,
    token_prefix     VARCHAR(4)   NOT NULL,
    created_at       DATETIME     NULL,
    PRIMARY KEY (organization_id, provider_id),
    UNIQUE KEY uq_provider_prefix (organization_id, token_prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One day's exception to a doctor's usual daily limit: a different limit (cap), or closed. No row = the usual limit.
CREATE TABLE provider_day (
    organization_id  BIGINT       NOT NULL,
    provider_id      BIGINT       NOT NULL,
    visit_date       DATE         NOT NULL,
    cap              INT          NULL,
    closed           BIT(1)       NOT NULL DEFAULT b'0',
    updated_by       BIGINT       NULL,
    updated_at       DATETIME     NULL,
    PRIMARY KEY (organization_id, provider_id, visit_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
