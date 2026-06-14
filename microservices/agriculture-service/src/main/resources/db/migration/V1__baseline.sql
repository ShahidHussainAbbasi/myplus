-- Flyway baseline — generated from the live myplusdb_agriculture schema (created by ddl-auto:update).
-- FK checks disabled so table create-order is irrelevant on a fresh DB.
SET FOREIGN_KEY_CHECKS=0;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agriculture_expense` (
  `agri_expense_id` bigint NOT NULL AUTO_INCREMENT,
  `amount` float DEFAULT NULL,
  `crop_name` varchar(255) DEFAULT NULL,
  `crop_type` varchar(255) DEFAULT NULL,
  `dated` date DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `expense_name` varchar(255) DEFAULT NULL,
  `expense_type` varchar(255) DEFAULT NULL,
  `land_id` bigint DEFAULT NULL,
  `land_name` varchar(255) DEFAULT NULL,
  `updated` date DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `user_type` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`agri_expense_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agriculture_income` (
  `agri_income_id` bigint NOT NULL AUTO_INCREMENT,
  `amount` float DEFAULT NULL,
  `crop_name` varchar(255) DEFAULT NULL,
  `crop_type` varchar(255) DEFAULT NULL,
  `dated` date DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `income_name` varchar(255) DEFAULT NULL,
  `income_type` varchar(255) DEFAULT NULL,
  `land_id` bigint DEFAULT NULL,
  `land_name` varchar(255) DEFAULT NULL,
  `updated` date DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `user_type` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`agri_income_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `land` (
  `land_id` bigint NOT NULL AUTO_INCREMENT,
  `dated` date DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `land_name` varchar(255) NOT NULL,
  `land_type` varchar(255) DEFAULT NULL,
  `land_unit` varchar(255) DEFAULT NULL,
  `total_land_unit` varchar(255) DEFAULT NULL,
  `updated` date DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `user_type` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`land_id`),
  UNIQUE KEY `UKtff19uxj3gwhj7pjt3nxogss8` (`land_name`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
SET FOREIGN_KEY_CHECKS=1;
