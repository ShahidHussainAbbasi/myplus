# TLS for the ALB (A3). DNS is hosted at Hostinger (not Route 53), so validation + the subdomain
# alias are added manually there. Flow:
#   1. `terraform apply` creates the cert (PENDING_VALIDATION) and prints `acm_validation_record`.
#   2. Add that CNAME at Hostinger. ACM detects it → cert ISSUED → apply continues, creates the 443
#      listener (see alb.tf).
#   3. Add a CNAME `api` -> the ALB DNS name (output `alb_dns_name`) at Hostinger.

resource "aws_acm_certificate" "api" {
  domain_name       = var.domain_name
  validation_method = "DNS"
  lifecycle { create_before_destroy = true }
  tags = { Name = "${var.project_name}-cert", Environment = var.environment }
}

# Waits until the cert is ISSUED (after you add the CNAME at Hostinger). No validation_record_fqdns
# because the records live in external DNS, not Route 53.
resource "aws_acm_certificate_validation" "api" {
  certificate_arn = aws_acm_certificate.api.arn
  timeouts { create = "60m" }
}

output "acm_validation_record" {
  description = "Add this CNAME at Hostinger to validate the ACM certificate."
  value = {
    name  = tolist(aws_acm_certificate.api.domain_validation_options)[0].resource_record_name
    type  = tolist(aws_acm_certificate.api.domain_validation_options)[0].resource_record_type
    value = tolist(aws_acm_certificate.api.domain_validation_options)[0].resource_record_value
  }
}

output "app_dns_cname" {
  description = "After the ALB exists, add this CNAME at Hostinger so the subdomain points at the ALB."
  value       = "${var.domain_name}  CNAME  ${aws_lb.main.dns_name}"
}
