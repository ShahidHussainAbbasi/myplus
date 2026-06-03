output "ecr_repositories" {
  value       = { for k, v in aws_ecr_repository.services : k => v.repository_url }
  description = "ECR repository URLs for all services"
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "api_gateway_url" {
  value       = "http://${aws_lb.main.dns_name}"
  description = "Public API Gateway URL"
}

output "vpc_id" {
  value = aws_vpc.main.id
}
