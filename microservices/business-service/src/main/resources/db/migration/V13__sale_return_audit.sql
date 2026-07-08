-- SF-11: audit log (credit-note stub) for sale returns. Returns used to mutate the invoice in place with no
-- trace; this table records who/what/why/how-much per return so there is an audit trail (and a basis for a
-- printable credit note later). Tenant-scoped (organization_id + user_id). IDENTITY pk (auto_increment) — no seq
-- table needed. New feature, no backfill (past returns left no reason to recover).
CREATE TABLE IF NOT EXISTS `sale_return` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `invoice_no`      VARCHAR(255) NULL,
  `sell_id`         BIGINT       NULL,
  `product_id`      BIGINT       NULL,
  `quantity`        FLOAT        NULL,
  `reason`          VARCHAR(255) NULL,
  `refund_amount`   DECIMAL(19,2) NULL,
  `organization_id` BIGINT       NULL,
  `user_id`         BIGINT       NULL,
  `dated`           DATETIME     NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sale_return_scope` (`organization_id`, `user_id`),
  KEY `idx_sale_return_invoice` (`invoice_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
