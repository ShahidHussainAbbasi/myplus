-- HMS S3b-1 — the doctor's prescription (docs/hms-phase1-design.md §4d). Additive only.
--
-- encounter_rx_item is the doctor's WORKING list for the visit: replaced as a whole on Save, FROZEN once submitted.
-- Nothing reaches the pharmacy until Submit, so a parked visit's prescription is invisible there by construction (06b).
-- encounter.rx_id is the pharma-service prescription the Submit made; set once, never cleared.
CREATE TABLE encounter_rx_item (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    encounter_id     BIGINT        NOT NULL,
    line_no          INT           NOT NULL,
    product_id       BIGINT        NOT NULL,
    medicine_name    VARCHAR(200)  NOT NULL,
    quantity         INT           NOT NULL,
    dosage           VARCHAR(100)  NULL,
    frequency        VARCHAR(100)  NULL,
    duration         VARCHAR(100)  NULL,
    PRIMARY KEY (id),
    KEY idx_rx_item_encounter (organization_id, encounter_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE encounter
    ADD COLUMN rx_id           BIGINT   NULL,
    ADD COLUMN rx_submitted_at DATETIME NULL;
