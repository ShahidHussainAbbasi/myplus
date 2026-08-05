-- B2B Phase 3g -- the fields a printable TRADE invoice needs, plus owner-designable document layouts.
--
-- Driven by a real pharmaceutical distribution invoice supplied by the customer. Phase 3b-1 made a trade sale
-- print the WORD "INVOICE"; everything else about the document stayed a 4-column 80mm till slip. Most of the
-- gap turned out to be rendering (the product code, per-line discount, due date and the customer's address
-- and mobile were ALREADY on the wire and simply thrown away). These are the columns that genuinely did not
-- exist anywhere.
--
-- WHY customer_history.trade_discount: an invoice-level concession, distinct from the per-line discounts
-- already carried on sell.discount. A distribution invoice settles a whole-order discount on one line at the
-- foot of the document, and before this there was NO discount column on the invoice header at all.
--
-- WHY customer_history.booked_by_name is a NAME and not a join: it is STAMPED at write time. Resolving
-- user_id at print time would put an auth-service round trip on the print path -- another service being down
-- would stop a shop printing a receipt -- and would also print a person's CURRENT name on a document issued
-- years ago. An issued document must not change after the fact.
--
-- WHY the licence lives on customer and NOT on the org: this is the BUYER's licence. A pharmaceutical
-- distributor may only supply a licensed reseller, and the invoice prints it as evidence. The SELLER's own
-- licence is a per-org SETTING (pos.document.licenseNo) because a business has one licence, not one per
-- invoice -- so it needs no schema at all.
--
-- WHY customer.city is separate from customer.address: address is a single free-text line. There is no
-- reliable way to recover a city from it afterwards, so it is captured rather than parsed.
--
-- WHY document_template is a TABLE and not an org_setting row: org_setting.setting_value is VARCHAR(500) --
-- a layout does not fit -- and SettingsStore.findAll() loads every override each time the Configuration
-- screen opens, which would drag template bodies into an unrelated read. Settings keep only the BINDING
-- (which template each channel uses); the bodies live here.
--
-- ALL MODULES ARE LIVE: purely additive, every column nullable, no back-fill. An existing invoice has no
-- bonus, no trade discount and no booked-by, and prints exactly as it does today. An org with no
-- document_template rows falls back to a built-in preset, which reproduces today's receipt byte for byte.
--
-- Idempotent (D7): CREATE TABLE IF NOT EXISTS + information_schema-guarded ADD COLUMN / CREATE INDEX.

-- ---------------------------------------------------------------- line: free goods ("Bon." on the invoice)
--
-- A quantity, so it mirrors sell.quantity's scale rather than the money types. NOTE (decision D-2, open):
-- bonus is PRESENTATION ONLY in this slice -- it does not decrement inventory. Making it move stock has to
-- run through the sell<->stock saga and post to the GL at zero revenue, which is materially more than a
-- column and is deliberately not smuggled in here.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sell' AND COLUMN_NAME='bonus_quantity')=0,
    'ALTER TABLE sell ADD COLUMN bonus_quantity FLOAT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------- invoice header
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='trade_discount')=0,
    'ALTER TABLE customer_history ADD COLUMN trade_discount DECIMAL(19,2) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_history' AND COLUMN_NAME='booked_by_name')=0,
    'ALTER TABLE customer_history ADD COLUMN booked_by_name VARCHAR(120) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------- trade buyer identity
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='city')=0,
    'ALTER TABLE customer ADD COLUMN city VARCHAR(80) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='cnic')=0,
    'ALTER TABLE customer ADD COLUMN cnic VARCHAR(20) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='license_no')=0,
    'ALTER TABLE customer ADD COLUMN license_no VARCHAR(60) DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='license_expiry')=0,
    'ALTER TABLE customer ADD COLUMN license_expiry DATE DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------- owner-designed document layouts
--
-- profile_json holds a DECLARATIVE Document Profile: paper size, header field groups, the line columns with
-- their labels/widths/alignment, which totals rows print, footer. It is NOT a template language and NOT
-- markup -- every field is referenced by a KEY that must exist in the renderer's whitelist, which is what
-- keeps an owner-authored invoice XSS-safe, translatable and upgradeable. ProfileValidator enforces that
-- server-side on save; the client is never trusted to have done it.
--
-- UNIQUE(organization_id, doc_type, channel, name) makes a template addressable by a stable natural key
-- per tenant and stops a duplicate name silently shadowing another.
CREATE TABLE IF NOT EXISTS document_template (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       DEFAULT NULL,
    user_id          BIGINT       DEFAULT NULL,
    doc_type         VARCHAR(24)  NOT NULL DEFAULT 'SALE',
    channel          VARCHAR(8)   DEFAULT NULL,        -- B2B | B2C | NULL = either
    name             VARCHAR(120) NOT NULL,
    profile_json     TEXT         NOT NULL,
    is_default       TINYINT(1)   NOT NULL DEFAULT 0,
    version          INT          NOT NULL DEFAULT 1,
    created_at       DATETIME     DEFAULT NULL,
    updated_at       DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_document_template (organization_id, doc_type, channel, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The resolver's access path: "the template this org uses for this document type and channel".
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='document_template' AND INDEX_NAME='idx_doctpl_org_type')=0,
    'CREATE INDEX idx_doctpl_org_type ON document_template (organization_id, doc_type, channel)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
