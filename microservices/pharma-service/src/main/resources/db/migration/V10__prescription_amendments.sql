-- HMS H3b — the pharmacist changes or cancels a prescription, and the record says so (client ruling L-3;
-- docs/hms-phase1-design.md §4h). Additive only.
--
-- The client chose that the pharmacist may change THE PRESCRIPTION ITSELF (not only the sale). The current lines are
-- what the pharmacy dispenses; this table keeps every change — who, when, why, and each line before → after — so the
-- doctor's original is never lost and the doctor sees what the pharmacy did.
--   kind     AMEND | CANCEL
--   changes  compact JSON of the lines that changed (≤ 30 lines a prescription; 8000 characters is ample)
CREATE TABLE prescription_amendment (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    prescription_id  BIGINT        NOT NULL,
    kind             VARCHAR(16)   NOT NULL,
    reason           VARCHAR(500)  NOT NULL,
    changes          VARCHAR(8000) NULL,
    amended_by       BIGINT        NULL,
    amended_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_rx_amendment (organization_id, prescription_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
