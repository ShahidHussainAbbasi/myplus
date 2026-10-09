-- EX-5 — receipts: the evidence behind an expense. The FILE lives in the ReceiptStore (a disk volume now, S3 later —
-- blobs do not belong in MySQL); this row is its metadata. A receipt is uploaded first (voucher_id NULL) and attached
-- when the expense is saved, so "a receipt is required above X" can be enforced at save. Removing one is a soft
-- remove (removed_at): evidence is kept. sha256 finds the same receipt used twice.
CREATE TABLE IF NOT EXISTS expense_receipt (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id BIGINT        NOT NULL,
    voucher_id      BIGINT        NULL,
    store_key       VARCHAR(200)  NOT NULL,
    content_type    VARCHAR(40)   NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    sha256          VARCHAR(64)   NOT NULL,
    original_name   VARCHAR(160)  NULL,
    uploaded_by     BIGINT        NULL,
    uploaded_at     DATETIME      NOT NULL,
    removed_at      DATETIME      NULL,
    removed_by      BIGINT        NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_expense_receipt_key (store_key),
    KEY idx_expense_receipt_voucher (voucher_id),
    KEY idx_expense_receipt_sha (organization_id, sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
