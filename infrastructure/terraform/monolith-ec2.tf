# Monolith on the existing EC2 (not Fargate-ized). The EC2 itself is NOT managed here (it's yours);
# this only opens RDS to it so the monolith can reach `myplusdb`. Set var.monolith_security_group_id
# to the EC2's security-group id. Full setup: docs/monolith-ec2-deployment.md.

variable "monolith_security_group_id" {
  description = "Security-group id of the existing monolith EC2. If set, RDS allows MySQL (3306) from it."
  type        = string
  default     = ""
}

resource "aws_security_group_rule" "rds_ingress_monolith" {
  count                    = var.monolith_security_group_id == "" ? 0 : 1
  type                     = "ingress"
  from_port                = 3306
  to_port                  = 3306
  protocol                 = "tcp"
  security_group_id        = aws_security_group.rds.id
  source_security_group_id = var.monolith_security_group_id
  description              = "MySQL from the monolith EC2"
}
