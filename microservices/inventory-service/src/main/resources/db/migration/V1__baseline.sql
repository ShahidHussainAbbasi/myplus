-- Flyway baseline — generated from the live myplusdb_inventory schema (created by ddl-auto:update).
-- FK checks disabled so table create-order is irrelevant on a fresh DB.
SET FOREIGN_KEY_CHECKS=0;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `categories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `parent_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKsaok720gsu4u2wrgbk10b5n8d` (`parent_id`),
  CONSTRAINT `FKsaok720gsu4u2wrgbk10b5n8d` FOREIGN KEY (`parent_id`) REFERENCES `categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cost_price` decimal(38,2) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `current_stock` float DEFAULT NULL,
  `description` varchar(2000) DEFAULT NULL,
  `image_url` varchar(255) DEFAULT NULL,
  `is_active` bit(1) DEFAULT NULL,
  `max_stock_level` float DEFAULT NULL,
  `min_stock_level` float DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `reorder_point` float DEFAULT NULL,
  `selling_price` decimal(38,2) DEFAULT NULL,
  `sku` varchar(255) NOT NULL,
  `tax_rate` decimal(38,2) DEFAULT NULL,
  `unit` varchar(255) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `category_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKfhmd06dsmj6k0n90swsh8ie9g` (`sku`),
  KEY `FKog2rp4qthbtt2lfyhfo32lsw9` (`category_id`),
  CONSTRAINT `FKog2rp4qthbtt2lfyhfo32lsw9` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_adjustments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `adjusted_at` datetime(6) DEFAULT NULL,
  `adjusted_by` bigint DEFAULT NULL,
  `adjustment_type` enum('DECREASE','INCREASE','TRANSFER') NOT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `quantity` float NOT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `product_id` bigint NOT NULL,
  `warehouse_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKh0emjv0ifyv4bpghf1pfcaue4` (`product_id`),
  KEY `FKrx0rcvfnre9vgtayn08ax20f5` (`warehouse_id`),
  CONSTRAINT `FKh0emjv0ifyv4bpghf1pfcaue4` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `FKrx0rcvfnre9vgtayn08ax20f5` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouses` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_alerts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `alert_type` enum('EXPIRY_NEAR','LOW_STOCK','OUT_OF_STOCK') DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `is_read` bit(1) DEFAULT NULL,
  `message` varchar(500) DEFAULT NULL,
  `product_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKpqujag9ghj2w9prm5gdatxvos` (`product_id`),
  CONSTRAINT `FKpqujag9ghj2w9prm5gdatxvos` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_no` varchar(255) DEFAULT NULL,
  `entry_date` datetime(6) DEFAULT NULL,
  `expiry_date` date DEFAULT NULL,
  `lot_no` varchar(255) DEFAULT NULL,
  `notes` varchar(1000) DEFAULT NULL,
  `purchase_price` decimal(38,2) DEFAULT NULL,
  `quantity` float NOT NULL,
  `supplier_id` bigint DEFAULT NULL,
  `product_id` bigint NOT NULL,
  `warehouse_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK8v0x1aaabbwpklu701febdgqr` (`product_id`),
  KEY `FKf65p1gva2groind16qbb2spyc` (`warehouse_id`),
  CONSTRAINT `FK8v0x1aaabbwpklu701febdgqr` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `FKf65p1gva2groind16qbb2spyc` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouses` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_transfers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `notes` varchar(1000) DEFAULT NULL,
  `quantity` float NOT NULL,
  `status` enum('CANCELLED','COMPLETED','PENDING') DEFAULT NULL,
  `transfer_date` datetime(6) DEFAULT NULL,
  `transferred_by` bigint DEFAULT NULL,
  `from_warehouse_id` bigint DEFAULT NULL,
  `product_id` bigint NOT NULL,
  `to_warehouse_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK1a7ck2ceraf7yvx8oyalg6qcl` (`from_warehouse_id`),
  KEY `FK8wdmt04gphikujrrpmianl6xw` (`product_id`),
  KEY `FKtg575pm9p5bbyj3ki1ssffcpc` (`to_warehouse_id`),
  CONSTRAINT `FK1a7ck2ceraf7yvx8oyalg6qcl` FOREIGN KEY (`from_warehouse_id`) REFERENCES `warehouses` (`id`),
  CONSTRAINT `FK8wdmt04gphikujrrpmianl6xw` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `FKtg575pm9p5bbyj3ki1ssffcpc` FOREIGN KEY (`to_warehouse_id`) REFERENCES `warehouses` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `suppliers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `contact_person` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `is_active` bit(1) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `payment_terms` varchar(255) DEFAULT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `tax_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `warehouses` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `capacity` float DEFAULT NULL,
  `location` varchar(255) DEFAULT NULL,
  `manager_id` bigint DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
SET FOREIGN_KEY_CHECKS=1;
