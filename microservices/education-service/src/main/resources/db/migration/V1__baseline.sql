-- Flyway baseline — generated from the live myplusdb_education schema (created by ddl-auto:update).
-- FK checks disabled so table create-order is irrelevant on a fresh DB.
SET FOREIGN_KEY_CHECKS=0;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alert` (
  `alert_id` bigint NOT NULL AUTO_INCREMENT,
  `alert_heading` varchar(255) DEFAULT NULL,
  `alert_signature` varchar(255) DEFAULT NULL,
  `alert_message` varchar(255) DEFAULT NULL,
  `alert_type` varchar(255) DEFAULT NULL,
  `consumers` varchar(255) DEFAULT NULL,
  `delivery_channel` varchar(255) DEFAULT NULL,
  `delivery_type` varchar(255) DEFAULT NULL,
  `delivery_period` varchar(255) DEFAULT NULL,
  `end_date` date DEFAULT NULL,
  `start_date` date DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `user_type` bigint DEFAULT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`alert_id`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alert_channel` (
  `alert_channel_id` bigint NOT NULL AUTO_INCREMENT,
  `channel` varchar(255) NOT NULL,
  `channel_name` varchar(255) DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `user_type` varchar(255) DEFAULT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`alert_channel_id`)
) ENGINE=InnoDB AUTO_INCREMENT=77 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `attendance` (
  `attendance_id` bigint NOT NULL AUTO_INCREMENT,
  `dated_time` datetime(6) DEFAULT NULL,
  `enroll_no` varchar(255) DEFAULT NULL,
  `grade_name` varchar(255) DEFAULT NULL,
  `grade_id` bigint DEFAULT NULL,
  `time_in` time(6) DEFAULT NULL,
  `time_out` time(6) DEFAULT NULL,
  `remarks` varchar(255) DEFAULT NULL,
  `student_name` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `organization_id` bigint DEFAULT NULL,
  `att_date` date DEFAULT NULL,
  PRIMARY KEY (`attendance_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `discount` (
  `discount_id` bigint NOT NULL AUTO_INCREMENT,
  `amount` int DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `discount_in` varchar(255) DEFAULT NULL,
  `end_date` date DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `reference_mobile` varchar(255) DEFAULT NULL,
  `reference_name` varchar(255) DEFAULT NULL,
  `start_date` date DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`discount_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fee_collection` (
  `fc_id` bigint NOT NULL AUTO_INCREMENT,
  `check_no` varchar(255) DEFAULT NULL,
  `discount` int DEFAULT NULL,
  `due_amount` int DEFAULT NULL,
  `due_balance` int DEFAULT NULL,
  `due_day_of_month` int DEFAULT NULL,
  `discount_type` varchar(255) DEFAULT NULL,
  `enroll_no` varchar(255) DEFAULT NULL,
  `fee` int DEFAULT NULL,
  `fee_paid` int DEFAULT NULL,
  `other_dues` int DEFAULT NULL,
  `other_dues_description` varchar(255) DEFAULT NULL,
  `payee` varchar(255) DEFAULT NULL,
  `payment_date` date DEFAULT NULL,
  `recieved_by` varchar(255) DEFAULT NULL,
  `recieved_in` varchar(255) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `vehicle_fee` int DEFAULT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`fc_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fee_setting` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `aging_enabled` bit(1) DEFAULT NULL,
  `auto_register_dues` bit(1) DEFAULT NULL,
  `due_day` int DEFAULT NULL,
  `fee_cycle` varchar(255) DEFAULT NULL,
  `organization_id` bigint NOT NULL,
  `payment_mode` varchar(255) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_fee_setting_org` (`organization_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `grade` (
  `grade_id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(255) DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `fee` float DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `room` bigint DEFAULT NULL,
  `school_id` bigint DEFAULT NULL,
  `section` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `time_from` time(6) DEFAULT NULL,
  `time_to` time(6) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`grade_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `guardian` (
  `guardian_id` bigint NOT NULL AUTO_INCREMENT,
  `cnic` varchar(255) DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `gender` varchar(255) DEFAULT NULL,
  `mobile` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `occupation` varchar(255) DEFAULT NULL,
  `perm_address` varchar(255) DEFAULT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `relation` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `temp_address` varchar(255) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`guardian_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `owner` (
  `owner_id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `mobile` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `status` varchar(255) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`owner_id`),
  UNIQUE KEY `uq_owner_org_name` (`organization_id`,`name`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `school` (
  `school_id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `branch_name` varchar(255) DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`school_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `schools_owners` (
  `school_id` bigint NOT NULL,
  `owner_id` bigint NOT NULL,
  PRIMARY KEY (`school_id`,`owner_id`),
  KEY `FKlo3ktfq2vymfp4vdhg9n72cu9` (`owner_id`),
  CONSTRAINT `FKi9ifnn4so48id4inod3gx4fad` FOREIGN KEY (`school_id`) REFERENCES `school` (`school_id`),
  CONSTRAINT `FKlo3ktfq2vymfp4vdhg9n72cu9` FOREIGN KEY (`owner_id`) REFERENCES `owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `staff` (
  `staff_id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `designation` varchar(255) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `gender` varchar(255) DEFAULT NULL,
  `martial_status` varchar(255) DEFAULT NULL,
  `mobile` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `qualification` varchar(255) DEFAULT NULL,
  `date_of_birth` date DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `time_in` time(6) DEFAULT NULL,
  `time_out` time(6) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`staff_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `staff_grades` (
  `staff_id` bigint NOT NULL,
  `grade_id` bigint NOT NULL,
  KEY `FKpev5wa2jn60sxpdty4nfulsid` (`grade_id`),
  KEY `FK2ohtpwob9js54pt8a87qy86o3` (`staff_id`),
  CONSTRAINT `FK2ohtpwob9js54pt8a87qy86o3` FOREIGN KEY (`staff_id`) REFERENCES `staff` (`staff_id`),
  CONSTRAINT `FKpev5wa2jn60sxpdty4nfulsid` FOREIGN KEY (`grade_id`) REFERENCES `grade` (`grade_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `student` (
  `student_id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `blood_group` varchar(255) DEFAULT NULL,
  `date_of_birth` date DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `discount_in` varchar(255) DEFAULT NULL,
  `discount_id` bigint DEFAULT NULL,
  `due_day` int DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `enroll_date` date DEFAULT NULL,
  `enroll_no` varchar(255) DEFAULT NULL,
  `fee` float DEFAULT NULL,
  `fee_mode` varchar(255) DEFAULT NULL,
  `gender` varchar(255) DEFAULT NULL,
  `grade_id` bigint DEFAULT NULL,
  `guardian_id` bigint DEFAULT NULL,
  `mother_name` varchar(255) DEFAULT NULL,
  `mobile` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `new_discount` int DEFAULT NULL,
  `place_0f_birth` varchar(255) DEFAULT NULL,
  `religion` varchar(255) DEFAULT NULL,
  `school_id` bigint DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `vehicle_id` bigint DEFAULT NULL,
  `vehicle_fare` int DEFAULT NULL,
  `watts_app` varchar(255) DEFAULT NULL,
  `year_end` date DEFAULT NULL,
  `year_start` date DEFAULT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`student_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `subject` (
  `subject_id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(255) DEFAULT NULL,
  `dated` datetime(6) DEFAULT NULL,
  `edition` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `publisher` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `grade_id` bigint DEFAULT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`subject_id`),
  KEY `FKam8igh6e0xp9r1tl7d8r1wneb` (`grade_id`),
  CONSTRAINT `FKam8igh6e0xp9r1tl7d8r1wneb` FOREIGN KEY (`grade_id`) REFERENCES `grade` (`grade_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `vehicle` (
  `vehicle_id` bigint NOT NULL AUTO_INCREMENT,
  `dated` datetime(6) DEFAULT NULL,
  `driver_mobile` varchar(255) DEFAULT NULL,
  `driver_name` varchar(255) DEFAULT NULL,
  `vehicle_name` varchar(255) NOT NULL,
  `vehicle_number` varchar(255) NOT NULL,
  `owner_mobile` varchar(255) DEFAULT NULL,
  `owner_name` varchar(255) DEFAULT NULL,
  `school_id` bigint DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `updated` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `organization_id` bigint DEFAULT NULL,
  PRIMARY KEY (`vehicle_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
SET FOREIGN_KEY_CHECKS=1;
