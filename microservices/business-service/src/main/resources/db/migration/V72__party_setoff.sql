-- DR-4 — set-off: one partner who is both our customer and our supplier settles what they owe us against what we owe
-- them, by agreement. Design: microservices/docs/party-dual-role-customer-supplier-analysis.md (DR-4).
--
-- party_setoff       the document (SETOFF-000001, per-org series). idempotency_key and reversal_key are UNIQUE per
--                    org, so a double click or a retry replays the first instead of settling twice.
-- party_setoff_alloc exactly which invoices and bills the set-off cleared, and by how much — a reversal re-opens these
--                    rows and nothing else. Never derived again from the documents, which later payments change.
CREATE TABLE IF NOT EXISTS party_setoff (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT         NULL,
    user_id          BIGINT         NULL,
    setoff_no        VARCHAR(20)    NOT NULL,
    party_id         BIGINT         NOT NULL,
    customer_id      BIGINT         NOT NULL,
    vender_id        BIGINT         NOT NULL,
    amount           DECIMAL(19,2)  NOT NULL,
    reason           VARCHAR(255)   NOT NULL,
    reference        VARCHAR(120)   NULL,
    receipt_no       VARCHAR(40)    NULL,
    voucher_no       VARCHAR(40)    NULL,
    idempotency_key  VARCHAR(80)    NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    created_at       DATETIME       NULL,
    reversed_by      BIGINT         NULL,
    reversed_at      DATETIME       NULL,
    reversal_reason  VARCHAR(255)   NULL,
    reversal_key     VARCHAR(80)    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_party_setoff_org_no (organization_id, setoff_no),
    UNIQUE KEY uq_party_setoff_org_idem (organization_id, idempotency_key),
    UNIQUE KEY uq_party_setoff_org_rev (organization_id, reversal_key),
    KEY idx_party_setoff_org_party (organization_id, party_id),
    KEY idx_party_setoff_customer (customer_id),
    KEY idx_party_setoff_vender (vender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS party_setoff_alloc (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    setoff_id  BIGINT         NOT NULL,
    side       VARCHAR(10)    NOT NULL,
    doc_type   VARCHAR(20)    NOT NULL,
    doc_id     BIGINT         NOT NULL,
    doc_no     VARCHAR(60)    NULL,
    amount     DECIMAL(19,2)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_party_setoff_alloc_setoff (setoff_id),
    CONSTRAINT fk_party_setoff_alloc_setoff FOREIGN KEY (setoff_id) REFERENCES party_setoff (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
