-- Audit #4 (GL posting reliability): transactional outbox. Producers (sale/purchase/return/edit) write a PENDING
-- row here IN THE SAME TX as the business change, so a GL event can never be silently lost if finance-service is
-- momentarily down. An afterCommit hook delivers immediately; a @Scheduled relay re-drives anything still PENDING
-- (impersonating the tenant via runAs). Structured columns map straight to PostingEventRequest. Additive, prod-safe.
CREATE TABLE IF NOT EXISTS `gl_outbox` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `event_type`      VARCHAR(20)   NOT NULL,   -- SALE | PURCHASE | SALE_RETURN | PURCHASE_RETURN
  `ref`             VARCHAR(255)  NULL,       -- invoiceNo / purchaseInvoiceNo
  `grand_total`     DECIMAL(19,2) NULL,
  `sub_total`       DECIMAL(19,2) NULL,
  `tax_total`       DECIMAL(19,2) NULL,
  `cost`            DECIMAL(19,2) NULL,
  `paid_amount`     DECIMAL(19,2) NULL,
  `method`          VARCHAR(30)   NULL,
  `status`          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',   -- PENDING | POSTED | FAILED
  `attempts`        INT           NOT NULL DEFAULT 0,
  `last_error`      VARCHAR(500)  NULL,
  `organization_id` BIGINT        NULL,       -- for runAs on the relay
  `user_id`         BIGINT        NULL,
  `created_at`      DATETIME      NULL,
  `updated_at`      DATETIME      NULL,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_pending` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
