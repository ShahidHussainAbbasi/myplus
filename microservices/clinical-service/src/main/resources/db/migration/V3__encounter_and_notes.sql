-- HMS S3a — the consultation (docs/hms-phase1-design.md §4c). Additive only.

-- One consultation per token: uq_encounter_token makes a double "Start" the SAME encounter, never two.
-- Vitals are typed columns, range-checked by EncounterRules before they are written (a temperature of 986 is a typo).
CREATE TABLE encounter (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    token_id         BIGINT        NOT NULL,
    patient_id       BIGINT        NOT NULL,
    provider_id      BIGINT        NOT NULL,
    doctor_user_id   BIGINT        NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    chief_complaint  VARCHAR(500)  NULL,
    bp_systolic      INT           NULL,
    bp_diastolic     INT           NULL,
    pulse            INT           NULL,
    temperature_f    DECIMAL(4,1)  NULL,
    spo2             INT           NULL,
    weight_kg        DECIMAL(5,1)  NULL,
    height_cm        DECIMAL(5,1)  NULL,
    started_at       DATETIME      NULL,
    completed_at     DATETIME      NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_encounter_token (organization_id, token_id),
    KEY idx_encounter_patient (organization_id, patient_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The doctor's notes. APPEND-ONLY: never updated, never deleted. A correction is a new row whose amends_note_id
-- points at the note it corrects, so the record shows what was written, when and by whom.
CREATE TABLE clinical_note (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    encounter_id     BIGINT        NOT NULL,
    patient_id       BIGINT        NOT NULL,
    author_user_id   BIGINT        NULL,
    body             VARCHAR(4000) NOT NULL,
    amends_note_id   BIGINT        NULL,
    created_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_note_encounter (organization_id, encounter_id, id),
    KEY idx_note_patient (organization_id, patient_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
