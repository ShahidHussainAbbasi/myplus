-- Flyway baseline — generated from the live myplusdb_analytics schema (created by ddl-auto:update).
-- FK checks disabled so table create-order is irrelevant on a fresh DB.
SET FOREIGN_KEY_CHECKS=0;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `aggregated_metrics` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `computed_at` datetime(6) DEFAULT NULL,
  `dimension` varchar(255) DEFAULT NULL,
  `metric_name` varchar(255) NOT NULL,
  `period_end` date DEFAULT NULL,
  `period_start` date DEFAULT NULL,
  `period_type` enum('DAILY','MONTHLY','WEEKLY') NOT NULL,
  `service_source` varchar(255) DEFAULT NULL,
  `value` double NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dashboard_widgets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `config` text,
  `created_at` datetime(6) DEFAULT NULL,
  `data_source` varchar(255) DEFAULT NULL,
  `is_active` bit(1) NOT NULL,
  `position` int NOT NULL,
  `title` varchar(255) NOT NULL,
  `user_id` bigint NOT NULL,
  `widget_type` enum('BAR_CHART','COUNTER','LINE_CHART','PIE_CHART','TABLE') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `report_definitions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `description` text,
  `is_active` bit(1) NOT NULL,
  `last_run_at` datetime(6) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `schedule_type` enum('DAILY','MANUAL','MONTHLY','WEEKLY') NOT NULL,
  `type` enum('CUSTOM','FINANCIAL','INVENTORY','SALES','USER_ACTIVITY') NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `report_executions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `error_message` text,
  `result_data` longtext,
  `started_at` datetime(6) DEFAULT NULL,
  `status` enum('COMPLETED','FAILED','RUNNING') NOT NULL,
  `report_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKomqs0t8om0gx2djv8fbyy5v0n` (`report_id`),
  CONSTRAINT `FKomqs0t8om0gx2djv8fbyy5v0n` FOREIGN KEY (`report_id`) REFERENCES `report_definitions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
SET FOREIGN_KEY_CHECKS=1;
