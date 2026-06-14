-- Flyway baseline — generated from the live myplusdb_welfare schema (created by ddl-auto:update).
-- FK checks disabled so table create-order is irrelevant on a fresh DB.
SET FOREIGN_KEY_CHECKS=0;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `donation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` float DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `donator_name` varchar(255) DEFAULT NULL,
  `received_by` varchar(255) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `donator_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `donator` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `amount` float DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `f_name` varchar(255) DEFAULT NULL,
  `mobile` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `received_by` varchar(255) DEFAULT NULL,
  `show_me` bit(1) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `user_type` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
SET FOREIGN_KEY_CHECKS=1;
