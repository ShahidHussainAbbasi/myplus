-- F3 (General Ledger): double-entry core. accounts = the per-org chart of accounts; journal_entries = balanced
-- transactions (Σdebit = Σcredit, enforced in GlService); journal_lines = the debit/credit sides. Seeded chart is
-- created per org on demand by GlService.ensureDefaults() (Flyway can't know the orgs). IDENTITY pks, no seed rows.
CREATE TABLE IF NOT EXISTS `accounts` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `code`            VARCHAR(20)  NOT NULL,
  `name`            VARCHAR(255) NOT NULL,
  `type`            VARCHAR(20)  NOT NULL,   -- ASSET|LIABILITY|EQUITY|INCOME|EXPENSE
  `normal_side`     VARCHAR(10)  NOT NULL,   -- DEBIT|CREDIT
  `organization_id` BIGINT       NULL,
  PRIMARY KEY (`id`),
  KEY `idx_acct_org_code` (`organization_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `journal_entries` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `entry_date`      DATE         NOT NULL,
  `source`          VARCHAR(20)  NULL,       -- SALE|PURCHASE|RECEIPT|PAYMENT|MANUAL
  `source_ref`      VARCHAR(255) NULL,
  `memo`            VARCHAR(255) NULL,
  `status`          VARCHAR(20)  NULL,       -- POSTED (immutable)
  `organization_id` BIGINT       NULL,
  `user_id`         BIGINT       NULL,
  `created_at`      DATETIME     NULL,
  PRIMARY KEY (`id`),
  KEY `idx_je_org_date` (`organization_id`, `entry_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `journal_lines` (
  `id`         BIGINT        NOT NULL AUTO_INCREMENT,
  `entry_id`   BIGINT        NULL,
  `account_id` BIGINT        NOT NULL,
  `debit`      DECIMAL(19,2) NULL,
  `credit`     DECIMAL(19,2) NULL,
  `line_memo`  VARCHAR(255)  NULL,
  PRIMARY KEY (`id`),
  KEY `idx_jl_account` (`account_id`),
  KEY `idx_jl_entry` (`entry_id`),
  CONSTRAINT `fk_jl_entry` FOREIGN KEY (`entry_id`) REFERENCES `journal_entries` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
