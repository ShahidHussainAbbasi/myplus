resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-db-subnet-group"
  subnet_ids = aws_subnet.private[*].id
  tags       = { Name = "${var.project_name}-db-subnet-group" }
}

resource "aws_security_group" "rds" {
  name        = "${var.project_name}-rds-sg"
  description = "Security group for RDS MySQL"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_tasks.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.project_name}-rds-sg" }
}

resource "aws_db_instance" "mysql" {
  identifier                = "${var.project_name}-mysql"
  engine                    = "mysql"
  engine_version            = "8.0"
  instance_class            = "db.t3.medium"
  allocated_storage         = 50
  max_allocated_storage     = 200
  storage_encrypted         = true
  username                  = "root"
  password                  = var.db_password
  db_subnet_group_name      = aws_db_subnet_group.main.name
  vpc_security_group_ids    = [aws_security_group.rds.id]
  multi_az                  = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.project_name}-final-snapshot"
  backup_retention_period   = 7
  deletion_protection       = true
  tags                      = { Name = "${var.project_name}-mysql", Environment = var.environment }
}

output "rds_endpoint" {
  value = aws_db_instance.mysql.endpoint
}
