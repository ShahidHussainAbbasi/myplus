-- FP-4c — finance tells business what it cannot know: each supplier's open EXPENSE BILLS and ADVANCE.
-- Design: microservices/docs/slices/fp-4-payables-reads-switch.md section 4c
--
-- One row per supplier whose documents changed, written in the SAME transaction as the change (transactional outbox).
-- The row only names the supplier: the figures are computed when the row is SENT, so a retry after later changes sends
-- the newest figures. Delivered by common-outbox's OutboxRelay (retry, dead-letter, health).
CREATE TABLE IF NOT EXISTS payable_balance_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    user_id          BIGINT        NULL,
    party_type       VARCHAR(16)   NOT NULL,
    party_id         BIGINT        NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)  NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_payable_balance_outbox_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Every supplier already in the subledger is queued once, so business receives its figures on any deploy with no
-- manual step. Sending is idempotent (business keeps the newest stamp).
INSERT INTO payable_balance_outbox (organization_id, user_id, party_type, party_id, status, attempts, created_at, updated_at)
SELECT d.organization_id, NULL, d.party_type, d.party_id, 'PENDING', 0, NOW(), NOW()
FROM payable_doc d
WHERE d.party_id IS NOT NULL
GROUP BY d.organization_id, d.party_type, d.party_id;
