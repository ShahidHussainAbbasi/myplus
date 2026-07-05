-- finance-service baseline — shared payment ledger (AR subledger; GL-ready).
-- IF NOT EXISTS so it is safe whether Hibernate ddl-auto (dev) created the tables first or Flyway owns them (prod).
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE IF NOT EXISTS `payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `direction` varchar(20) NOT NULL,
  `party_type` varchar(20) NOT NULL,
  `party_id` bigint DEFAULT NULL,
  `party_name` varchar(255) DEFAULT NULL,
  `amount` decimal(19,2) NOT NULL,
  `method` varchar(30) DEFAULT NULL,
  `paid_on` date DEFAULT NULL,
  `reference` varchar(255) DEFAULT NULL,
  `source_module` varchar(30) DEFAULT NULL,
  `receipt_no` varchar(255) DEFAULT NULL,
  `note` varchar(255) DEFAULT NULL,
  `debit_account` varchar(255) DEFAULT NULL,
  `credit_account` varchar(255) DEFAULT NULL,
  `organization_id` bigint DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pay_party` (`organization_id`,`party_type`,`party_id`),
  KEY `idx_pay_user` (`user_id`,`party_type`,`party_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `payment_allocations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payment_id` bigint DEFAULT NULL,
  `doc_type` varchar(20) DEFAULT NULL,
  `doc_id` bigint DEFAULT NULL,
  `doc_no` varchar(255) DEFAULT NULL,
  `amount` decimal(19,2) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_alloc_doc` (`doc_type`,`doc_id`),
  KEY `idx_alloc_payment` (`payment_id`),
  CONSTRAINT `fk_alloc_payment` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS=1;
