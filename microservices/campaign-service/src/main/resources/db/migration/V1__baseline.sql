-- Flyway baseline — generated from the live myplusdb_campaign schema (created by ddl-auto:update).
-- FK checks disabled so table create-order is irrelevant on a fresh DB.
SET FOREIGN_KEY_CHECKS=0;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audience_members` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(255) DEFAULT NULL,
  `first_name` varchar(255) DEFAULT NULL,
  `is_active` bit(1) NOT NULL,
  `last_name` varchar(255) DEFAULT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `subscribed_at` datetime(6) DEFAULT NULL,
  `unsubscribed_at` datetime(6) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `audience_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKc4ps367jhiejy6ing8q9yngau` (`audience_id`),
  CONSTRAINT `FKc4ps367jhiejy6ing8q9yngau` FOREIGN KEY (`audience_id`) REFERENCES `campaign_audiences` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `campaign_audiences` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `description` text,
  `estimated_size` int NOT NULL,
  `name` varchar(255) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `campaign_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `clicked_at` datetime(6) DEFAULT NULL,
  `delivered_at` datetime(6) DEFAULT NULL,
  `error_message` text,
  `opened_at` datetime(6) DEFAULT NULL,
  `recipient_email` varchar(255) DEFAULT NULL,
  `recipient_phone` varchar(255) DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('BOUNCED','CLICKED','DELIVERED','OPENED','SENT','UNSUBSCRIBED') DEFAULT NULL,
  `campaign_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKh5cbax0fsriievahaa5o0hl8t` (`campaign_id`),
  CONSTRAINT `FKh5cbax0fsriievahaa5o0hl8t` FOREIGN KEY (`campaign_id`) REFERENCES `campaigns` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `campaign_segments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `member_count` int NOT NULL,
  `name` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `campaign_templates` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `html_content` text,
  `is_active` bit(1) NOT NULL,
  `name` varchar(255) NOT NULL,
  `subject` varchar(255) DEFAULT NULL,
  `text_content` text,
  `type` enum('EMAIL','SMS') NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `campaigns` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bounced` int NOT NULL,
  `budget` decimal(38,2) DEFAULT NULL,
  `clicked` int NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `content` text,
  `created_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `delivered` int NOT NULL,
  `description` text,
  `name` varchar(255) NOT NULL,
  `opened` int NOT NULL,
  `scheduled_at` datetime(6) DEFAULT NULL,
  `sent` int NOT NULL,
  `spent` decimal(38,2) DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `status` enum('CANCELLED','COMPLETED','DRAFT','PAUSED','RUNNING','SCHEDULED') NOT NULL,
  `subject` varchar(255) DEFAULT NULL,
  `template_id` bigint DEFAULT NULL,
  `total_recipients` int NOT NULL,
  `type` enum('EMAIL','PUSH','SMS','SOCIAL') NOT NULL,
  `unsubscribed` int NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
SET FOREIGN_KEY_CHECKS=1;
