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

variable "domain_name" {
  description = "Public hostname for the API gateway ALB. Use a SUBDOMAIN — the live apex stays untouched. DNS is at Hostinger, so cert + alias records are added there manually (see outputs)."
  type        = string
  default     = "api.maxtheservice.com"
}

variable "image_tag" {
  description = "Bootstrap image tag for the initial task definition. The CI then deploys immutable :<sha> revisions (the service ignores task_definition changes), so this is only the first-apply placeholder."
  type        = string
  default     = "latest"
}

# Secret values — NO defaults (never commit secrets). Provide at apply time via a git-ignored
# terraform.tfvars / TF_VAR_* env (ideally from your secret store). Terraform seeds the initial
# Secrets Manager version (see secrets.tf); rotate the live value in Secrets Manager afterward.
variable "db_password" {
  description = "MySQL RDS master password (>= strong)"
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "JWT signing secret shared by auth-service + api-gateway (>= 256-bit)"
  type        = string
  sensitive   = true
}

variable "internal_secret" {
  description = "Gateway<->service trust secret (X-Internal-Secret); identical across all services (F2)"
  type        = string
  sensitive   = true
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
