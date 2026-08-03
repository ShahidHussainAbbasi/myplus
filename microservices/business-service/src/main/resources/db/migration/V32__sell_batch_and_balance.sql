-- Batch traceability on the SALE side + a balance snapshot for the receipt
-- (slice b2b-P3b-2 = customer requirement #4).
--
-- WHY sell_batch exists at all: inventory-service already returns which batches a FEFO reservation consumed
-- (StockReservationResponse.picks -> StockPick{batchNo, quantity, expiryDate}), and its own javadoc says the
-- data is there "so the sale ... records exact batch traceability". Nothing has ever consumed it. Every sale
-- has known exactly which batches left the shelf and discarded it. This table stops that.
--
-- WHY a child table rather than sell.batch_no: FEFO legitimately splits ONE line across several batches when
-- the oldest batch cannot cover the quantity. A single column would silently keep one batch and drop the
-- rest -- lossy exactly in the case traceability exists for, a part-shipped line during a recall.
--
-- WHY customer_history.balance_after: Customer.dueAmount is the CURRENT balance. Printing it on a re-print of
-- a two-year-old invoice would show today's figure on yesterday's document. Snapshotting the balance at sale
-- time is the only way a reprint stays truthful. "Previous balance" is DERIVED from it (balance_after minus
-- this invoice's unpaid amount), so one column suffices -- storing both would let them disagree.
--
-- ALL MODULES ARE LIVE: purely additive. No back-fill -- an existing invoice has no batch rows and a NULL
-- balance snapshot, and reprints exactly as it does today.
--
-- Idempotent (D7): CREATE TABLE IF NOT EXISTS + information_schema-guarded ADD COLUMN / CREATE INDEX.

CREATE TABLE IF NOT EXISTS sell_batch (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    sell_id          BIGINT        NOT NULL,
    organization_id  BIGINT        DEFAULT NULL,
    product_id       BIGINT        DEFAULT NULL,
    batch_no         VARCHAR(64)   DEFAULT NULL,
    expiry_date      DATE          DEFAULT NULL,
    quantity         DECIMAL(19,3) DEFAULT NULL,
    created_at       DATETIME      DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The receipt reads every batch row for the lines of one invoice, so sell_id is the access path.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell_batch' AND INDEX_NAME='idx_sell_batch_sell')=0,
    'CREATE INDEX idx_sell_batch_sell ON sell_batch (sell_id)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- A recall asks the opposite question: "which sales contained batch X?" -- org-scoped, as every read is.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell_batch' AND INDEX_NAME='idx_sell_batch_org_batch')=0,
    'CREATE INDEX idx_sell_batch_org_batch ON sell_batch (organization_id, batch_no)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Balance owed by the customer immediately AFTER this invoice was recorded.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='balance_after')=0,
    'ALTER TABLE customer_history ADD COLUMN balance_after DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
