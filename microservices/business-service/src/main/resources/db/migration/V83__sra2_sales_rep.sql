-- SRA-2 (2026-10-14) — the SALES REP list, and the rep who usually covers a customer.
-- Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §5.
--
-- A rep is a PERSON on the business's list, not a login: Shafeeq's reps book on paper as well as by logging in, so
-- `user_id` is optional and links the rep to a login when they have one. One login is at most one rep (UNIQUE), or a
-- rep's login could book as either row and their sales would split. Names are unique per business, case-insensitively
-- by the collation, so the pickers never show two "Irfan"s. Nothing is deleted: an INACTIVE rep leaves the pickers and
-- keeps every sale that names them.
--
-- `customer.default_rep_id` REPLACES `customer.assigned_rep_user_id` (O7 D6a), which pointed at a LOGIN: a rep with
-- no login could never cover an outlet. The old column is copied across below and then no longer read; it is dropped
-- in a later release, once nothing older than this is running.

CREATE TABLE IF NOT EXISTS sales_rep (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    name             VARCHAR(120) NOT NULL,
    phone            VARCHAR(40)  NULL,
    user_id          BIGINT       NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_by       BIGINT       NULL,
    created_at       DATETIME     NULL,
    updated_at       DATETIME     NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sales_rep_org_name (organization_id, name),
    UNIQUE KEY uq_sales_rep_org_user (organization_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND COLUMN_NAME = 'default_rep_id') = 0,
    'ALTER TABLE customer ADD COLUMN default_rep_id BIGINT NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND INDEX_NAME = 'idx_customer_org_default_rep') = 0,
    'CREATE INDEX idx_customer_org_default_rep ON customer (organization_id, default_rep_id)',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Copy today's assignments across: one rep per (business, login) that covers an outlet, linked to that login. The
-- business database cannot read people's names (they live in auth), so the rep is named "Member #<id>" — the owner
-- renames it on the Sales reps screen. Idempotent: a rep already linked to that login is reused.
INSERT INTO sales_rep (organization_id, name, user_id, status, created_at, updated_at)
SELECT DISTINCT c.organization_id, CONCAT('Member #', c.assigned_rep_user_id), c.assigned_rep_user_id, 'ACTIVE', NOW(), NOW()
  FROM customer c
 WHERE c.assigned_rep_user_id IS NOT NULL AND c.organization_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sales_rep r
                    WHERE r.organization_id = c.organization_id AND r.user_id = c.assigned_rep_user_id);

UPDATE customer c
  JOIN sales_rep r ON r.organization_id = c.organization_id AND r.user_id = c.assigned_rep_user_id
   SET c.default_rep_id = r.id
 WHERE c.assigned_rep_user_id IS NOT NULL AND c.default_rep_id IS NULL;
