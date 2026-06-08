locals {
  # A13: business-service owns the shared `myplusdb` (the monolith is being moved off it); every other
  # service uses `myplusdb_<name>`. Non-DB services (eureka/config/gateway) get a URL but ignore it.
  service_db_name = {
    "business-service" = "myplusdb"
  }
}

resource "aws_ecs_cluster" "main" {
  name = "${var.project_name}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = { Name = "${var.project_name}-cluster", Environment = var.environment }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    base              = 1
    weight            = 100
    capacity_provider = "FARGATE"
  }
}

resource "aws_security_group" "ecs_tasks" {
  name        = "${var.project_name}-ecs-tasks-sg"
  description = "Security group for ECS tasks"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 0
    to_port         = 65535
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  ingress {
    from_port = 0
    to_port   = 65535
    protocol  = "tcp"
    self      = true
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.project_name}-ecs-sg" }
}

resource "aws_iam_role" "ecs_task_execution" {
  name = "${var.project_name}-ecs-task-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# A8: least-privilege — read ONLY the three myplus secrets (not the AWS-managed SecretsManagerReadWrite).
resource "aws_iam_role_policy" "ecs_secrets_read" {
  name = "${var.project_name}-ecs-secrets-read"
  role = aws_iam_role.ecs_task_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [
        aws_secretsmanager_secret.db_password.arn,
        aws_secretsmanager_secret.jwt_secret.arn,
        aws_secretsmanager_secret.internal_secret.arn
      ]
    }]
  })
}

resource "aws_cloudwatch_log_group" "services" {
  for_each          = var.services
  name              = "/ecs/myplus/${each.key}"
  retention_in_days = 14
  tags              = { Service = each.key }
}

resource "aws_ecs_task_definition" "services" {
  for_each                 = var.services
  family                   = "${var.project_name}-${each.key}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn

  container_definitions = jsonencode([{
    name  = each.key
    image = "${var.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/myplus/${each.key}:${var.image_tag}"
    portMappings = [{
      containerPort = each.value.port
      protocol      = "tcp"
    }]
    environment = [
      { name = "SPRING_DATASOURCE_URL", value = "jdbc:mysql://${aws_db_instance.mysql.endpoint}/${lookup(local.service_db_name, each.key, "myplusdb_${replace(each.key, "-service", "")}")}?createDatabaseIfNotExist=true&useSSL=true&requireSSL=true" },
      { name = "SPRING_DATASOURCE_USERNAME", value = "root" },
      { name = "EUREKA_URI", value = "http://eureka-server.${var.project_name}.local:8761/eureka" },
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" }
    ]
    # Injected from Secrets Manager at container start (never plaintext in the task def). INTERNAL_SECRET
    # turns on the gateway<->service trust enforcement (F2).
    secrets = [
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = aws_secretsmanager_secret.db_password.arn },
      { name = "JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt_secret.arn },
      { name = "INTERNAL_SECRET", valueFrom = aws_secretsmanager_secret.internal_secret.arn }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = "/ecs/myplus/${each.key}"
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }
    healthCheck = {
      command     = ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:${each.value.port}/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
  }])

  tags = { Name = "${var.project_name}-${each.key}", Environment = var.environment }
}

resource "aws_ecs_service" "services" {
  for_each        = var.services
  name            = each.key
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.services[each.key].arn
  desired_count   = each.value.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = each.key == "api-gateway" ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.gateway.arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services[each.key].arn
  }

  depends_on = [aws_iam_role_policy_attachment.ecs_task_execution]
  tags       = { Name = each.key, Environment = var.environment }

  # A6: the CI deploys immutable :<sha> task-definition revisions; Terraform owns the service but not
  # the running revision, so it doesn't revert deploys on the next apply.
  lifecycle {
    ignore_changes = [task_definition]
  }
}
