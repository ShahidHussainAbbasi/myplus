-- B2B Phase 4b — sales quotes: a priced OFFER with a number, a shelf life and an approval trail.
--
-- Distinct from the price CALCULATION commerce-pricing already does (/price/calculate). That is arithmetic;
-- this is a document that gets numbered, sent, accepted and converted — and must still say what it said when
-- the customer accepted it.
--
-- quote_seq mirrors invoice_seq (slice 22) and credit_note_seq (3c): a per-org running number whose UNIQUE
-- constraint is what makes MAX+1 allocation safe under concurrency. Without it two staff raising a quote in the
-- same second would both compute the same next number and one insert would win silently.
--
-- version is the optimistic lock, present from day one rather than retrofitted: two staff converting the same
-- quote at the same moment would otherwise produce TWO invoices for one offer — a customer billed twice.
--
-- There is deliberately NO expiry job and no `expired` flag. EXPIRED is derived from valid_until when the quote
-- is read (SalesQuote.effectiveStatus), so a quote nobody opened needs no background thread and no scheduled
-- write can silently change a customer-facing document.
--
-- Idempotent: guarded on information_schema, so re-running is a no-op.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sales_quote')=0,
'CREATE TABLE sales_quote (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    quote_seq             BIGINT       NULL,
    quote_no              VARCHAR(32)  NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT ''DRAFT'',
    customer_id           BIGINT       NULL,
    customer_name         VARCHAR(255) NULL,
    customer_po_number    VARCHAR(64)  NULL,
    valid_until           DATE         NULL,
    sub_total             DECIMAL(19,2) NULL,
    tax_total             DECIMAL(19,2) NULL,
    trade_discount        DECIMAL(19,2) NULL,
    grand_total           DECIMAL(19,2) NULL,
    notes                 VARCHAR(500) NULL,
    approved_by           BIGINT       NULL,
    approved_at           DATETIME     NULL,
    converted_invoice_no  VARCHAR(32)  NULL,
    organization_id       BIGINT       NULL,
    user_id               BIGINT       NULL,
    store_id              BIGINT       NULL,
    dated                 DATETIME     NULL,
    updated               DATETIME     NULL,
    version               BIGINT       NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_quote_org_seq UNIQUE (organization_id, quote_seq),
    KEY idx_quote_org_status (organization_id, status),
    KEY idx_quote_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- The buyer's PO reference travels quote -> invoice, and is STORED on the invoice rather than looked up through
-- the quote: a printed document must be self-contained, and must keep showing the reference it was issued with
-- even if the quote row is later archived.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='customer_po_number')=0,
    'ALTER TABLE customer_history ADD COLUMN customer_po_number VARCHAR(64) NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sales_quote_line')=0,
'CREATE TABLE sales_quote_line (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    quote_id      BIGINT        NULL,
    product_id    BIGINT        NULL,
    product_name  VARCHAR(255)  NULL,
    quantity      FLOAT         NULL,
    unit_price    DECIMAL(19,2) NULL,
    price_reason  VARCHAR(64)   NULL,
    discount      DECIMAL(19,2) NULL,
    line_total    DECIMAL(19,2) NULL,
    PRIMARY KEY (id),
    KEY idx_quote_line_quote (quote_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
