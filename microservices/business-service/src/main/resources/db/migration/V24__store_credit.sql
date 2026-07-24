-- Store credit (SF-5 Model B): a customer can hold redeemable store credit (a liability the org owes them). A return
-- can issue credit instead of cash; a later sale redeems it as a tender. customer.credit_balance is the cached balance
-- (like due_amount); store_credit_txn is the audit ledger it's summed from. Idempotent (dev ddl-auto:update too).

-- 1) Cached credit balance on the customer (+ issue/− redeem summed from the ledger).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='credit_balance')=0,
    'ALTER TABLE customer ADD COLUMN credit_balance decimal(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) The store-credit ledger.
CREATE TABLE IF NOT EXISTS store_credit_txn (
    id               bigint        NOT NULL AUTO_INCREMENT,
    organization_id  bigint        DEFAULT NULL,
    user_id          bigint        DEFAULT NULL,
    customer_id      bigint        DEFAULT NULL,
    amount           decimal(19,2) DEFAULT NULL,   -- + issue, - redeem
    reason           varchar(32)   DEFAULT NULL,   -- RETURN | REDEEM | ADJUST
    ref              varchar(64)   DEFAULT NULL,   -- invoice no
    store_id         bigint        DEFAULT NULL,
    dated            datetime(6)   DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_scredit_cust (organization_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 3) STORE_CREDIT tender: extend the payment.method enum (ddl-auto:update can't add enum values → "Data truncated").
ALTER TABLE payment MODIFY method
    enum('BANK_TRANSFER','CARD','CASH','CREDIT','INSURANCE','REFUND','STORE_CREDIT','WALLET') DEFAULT NULL;
