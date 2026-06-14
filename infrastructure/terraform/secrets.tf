# AWS Secrets Manager — the single source of runtime secrets (A1/A2/A4).
# Terraform seeds the initial version from the (no-default) variables; `ignore_changes` then lets you
# rotate the live value in Secrets Manager without Terraform reverting it on the next apply.

locals {
  secret_prefix = "${var.project_name}/${var.environment}"
}

resource "aws_secretsmanager_secret" "db_password" {
  name        = "${local.secret_prefix}/db_password"
  description = "RDS MySQL master password"
  tags        = { Environment = var.environment }
}
resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = var.db_password
  lifecycle { ignore_changes = [secret_string] }
}

resource "aws_secretsmanager_secret" "jwt_secret" {
  name        = "${local.secret_prefix}/jwt_secret"
  description = "JWT signing key (auth-service + api-gateway)"
  tags        = { Environment = var.environment }
}
resource "aws_secretsmanager_secret_version" "jwt_secret" {
  secret_id     = aws_secretsmanager_secret.jwt_secret.id
  secret_string = var.jwt_secret
  lifecycle { ignore_changes = [secret_string] }
}

resource "aws_secretsmanager_secret" "internal_secret" {
  name        = "${local.secret_prefix}/internal_secret"
  description = "Gateway<->service internal trust secret (X-Internal-Secret)"
  tags        = { Environment = var.environment }
}
resource "aws_secretsmanager_secret_version" "internal_secret" {
  secret_id     = aws_secretsmanager_secret.internal_secret.id
  secret_string = var.internal_secret
  lifecycle { ignore_changes = [secret_string] }
}
