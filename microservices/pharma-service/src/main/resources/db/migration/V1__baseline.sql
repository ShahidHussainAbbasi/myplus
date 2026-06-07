-- Flyway baseline — generated from the live myplusdb_pharma schema (created by ddl-auto:update).
-- FK checks disabled so table create-order is irrelevant on a fresh DB.
SET FOREIGN_KEY_CHECKS=0;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dispensing` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `dispensed_at` datetime(6) DEFAULT NULL,
  `dispensed_by` bigint DEFAULT NULL,
  `notes` varchar(255) DEFAULT NULL,
  `patient_name` varchar(255) DEFAULT NULL,
  `quantity` int NOT NULL,
  `total_amount` decimal(38,2) DEFAULT NULL,
  `unit_price` decimal(38,2) DEFAULT NULL,
  `medicine_id` bigint DEFAULT NULL,
  `prescription_item_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKatbydu94awvkr20unymex1les` (`medicine_id`),
  KEY `FKm6pqupw336bjl68vdtx7y8d06` (`prescription_item_id`),
  CONSTRAINT `FKatbydu94awvkr20unymex1les` FOREIGN KEY (`medicine_id`) REFERENCES `medicines` (`id`),
  CONSTRAINT `FKm6pqupw336bjl68vdtx7y8d06` FOREIGN KEY (`prescription_item_id`) REFERENCES `prescription_items` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `drug_categories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(255) DEFAULT NULL,
  `is_active` bit(1) NOT NULL,
  `name` varchar(255) NOT NULL,
  `parent_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `drug_interactions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` text,
  `recommendation` varchar(255) DEFAULT NULL,
  `severity` enum('MILD','MODERATE','SEVERE') NOT NULL,
  `medicine1_id` bigint DEFAULT NULL,
  `medicine2_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK4m99u024n2q5q80ksc2a2ce2n` (`medicine1_id`),
  KEY `FKd8p56xrvm3n8s9wi5xjj20og2` (`medicine2_id`),
  CONSTRAINT `FK4m99u024n2q5q80ksc2a2ce2n` FOREIGN KEY (`medicine1_id`) REFERENCES `medicines` (`id`),
  CONSTRAINT `FKd8p56xrvm3n8s9wi5xjj20og2` FOREIGN KEY (`medicine2_id`) REFERENCES `medicines` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `medicines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `brand_name` varchar(255) DEFAULT NULL,
  `contraindications` text,
  `controlled_substance` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `description` text,
  `form` enum('CAPSULE','CREAM','DROPS','INJECTION','OTHER','SYRUP','TABLET') DEFAULT NULL,
  `generic_name` varchar(255) DEFAULT NULL,
  `is_active` bit(1) NOT NULL,
  `manufacturer` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `requires_prescription` bit(1) NOT NULL,
  `side_effects` text,
  `storage_conditions` varchar(255) DEFAULT NULL,
  `strength` varchar(255) DEFAULT NULL,
  `unit` varchar(255) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `category_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK17gu6b4j4glb80ihoff39ubsi` (`category_id`),
  CONSTRAINT `FK17gu6b4j4glb80ihoff39ubsi` FOREIGN KEY (`category_id`) REFERENCES `drug_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pharmacy_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_no` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expiry_date` date DEFAULT NULL,
  `purchase_price` decimal(38,2) DEFAULT NULL,
  `quantity` int NOT NULL,
  `received_date` date DEFAULT NULL,
  `selling_price` decimal(38,2) DEFAULT NULL,
  `supplier` varchar(255) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `medicine_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKtgjiaaptpfvsga41handxywmr` (`medicine_id`),
  CONSTRAINT `FKtgjiaaptpfvsga41handxywmr` FOREIGN KEY (`medicine_id`) REFERENCES `medicines` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prescription_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `dispensed_quantity` int NOT NULL,
  `dosage` varchar(255) DEFAULT NULL,
  `duration` varchar(255) DEFAULT NULL,
  `frequency` varchar(255) DEFAULT NULL,
  `quantity` int NOT NULL,
  `medicine_id` bigint DEFAULT NULL,
  `prescription_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKacx7kmy9ksr443xpn5dccidro` (`medicine_id`),
  KEY `FK6uh7tdy2lv6sx34u1365acqsf` (`prescription_id`),
  CONSTRAINT `FK6uh7tdy2lv6sx34u1365acqsf` FOREIGN KEY (`prescription_id`) REFERENCES `prescriptions` (`id`),
  CONSTRAINT `FKacx7kmy9ksr443xpn5dccidro` FOREIGN KEY (`medicine_id`) REFERENCES `medicines` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prescriptions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `diagnosis` varchar(255) DEFAULT NULL,
  `dispensed_at` datetime(6) DEFAULT NULL,
  `dispensed_by` bigint DEFAULT NULL,
  `doctor_license` varchar(255) DEFAULT NULL,
  `doctor_name` varchar(255) DEFAULT NULL,
  `notes` varchar(255) DEFAULT NULL,
  `patient_name` varchar(255) NOT NULL,
  `patient_phone` varchar(255) DEFAULT NULL,
  `prescribed_date` date DEFAULT NULL,
  `status` enum('CANCELLED','EXPIRED','FULLY_DISPENSED','PARTIALLY_DISPENSED','PENDING') NOT NULL,
  `user_id` bigint DEFAULT NULL,
  `valid_until` date DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
SET FOREIGN_KEY_CHECKS=1;
