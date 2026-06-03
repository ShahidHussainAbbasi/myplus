variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "account_id" {
  description = "AWS account ID"
  type        = string
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "prod"
}

variable "project_name" {
  description = "Project name"
  type        = string
  default     = "myplus"
}

variable "db_password" {
  description = "MySQL RDS password"
  type        = string
  sensitive   = true
  default     = "Technology@2025!"
}

variable "jwt_secret" {
  description = "JWT secret key"
  type        = string
  sensitive   = true
  default     = "myplus-super-secret-jwt-key-2025-must-be-at-least-256-bits-long"
}

variable "services" {
  description = "Microservices config"
  type = map(object({
    port          = number
    cpu           = number
    memory        = number
    desired_count = number
  }))
  default = {
    "eureka-server"       = { port = 8761, cpu = 256, memory = 512, desired_count = 1 }
    "config-server"       = { port = 8888, cpu = 256, memory = 512, desired_count = 1 }
    "api-gateway"         = { port = 8765, cpu = 512, memory = 1024, desired_count = 2 }
    "auth-service"        = { port = 8081, cpu = 512, memory = 1024, desired_count = 2 }
    "inventory-service"   = { port = 8082, cpu = 512, memory = 1024, desired_count = 1 }
    "business-service"    = { port = 8083, cpu = 512, memory = 1024, desired_count = 1 }
    "education-service"   = { port = 8084, cpu = 256, memory = 512, desired_count = 1 }
    "welfare-service"     = { port = 8085, cpu = 256, memory = 512, desired_count = 1 }
    "agriculture-service" = { port = 8086, cpu = 256, memory = 512, desired_count = 1 }
    "pharma-service"      = { port = 8087, cpu = 512, memory = 1024, desired_count = 1 }
    "marketplace-service" = { port = 8088, cpu = 512, memory = 1024, desired_count = 1 }
    "campaign-service"    = { port = 8089, cpu = 256, memory = 512, desired_count = 1 }
    "analytics-service"   = { port = 8090, cpu = 512, memory = 1024, desired_count = 1 }
  }
}
