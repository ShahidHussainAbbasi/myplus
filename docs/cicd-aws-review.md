# CI/CD + AWS deployment review — findings

Review of `infrastructure/terraform/**` and `.github/workflows/**` (ECS Fargate behind an ALB, ECR,
RDS MySQL, GitHub Actions). Companion to the app-level hardening in `docs/prod-hardening.md`.
**Status: for review — no remediation applied yet.**

## What's already good
Private subnets + NAT; RDS encrypted, multi-AZ, private SG (only ECS tasks reach 3306), 7-day backups,
deletion protection; ECS Fargate with container insights, services in private subnets,
`assign_public_ip=false`; service discovery; ECR scan-on-push + lifecycle policy; path-filtered,
dependency-ordered builds; CloudWatch logs per service; ACM-ready ALB SG (443 open).

## Findings

| # | Sev | Location | Issue | Fix |
|---|-----|----------|-------|-----|
| **A1** | **Critical** | `variables.tf:24-36` | `db_password` / `jwt_secret` have **committed real defaults** (public in git). | Remove defaults; source from **AWS Secrets Manager** (or required TF vars from a secrets store). Rotate. |
| **A2** | **Critical** | `ecs.tf:98-105` | DB password + JWT secret injected as **plaintext `environment`** in the task definition → visible to anyone with `ecs:DescribeTaskDefinition`. | Use ECS `secrets` (`valueFrom` Secrets Manager ARN), not `environment`. |
| **A3** | **High** | `alb.tf:57-66` | **No TLS** — only an HTTP:80 listener (the 443 SG rule has no listener). All traffic plaintext. | ACM cert + HTTPS:443 listener (forward) + HTTP:80 → 443 redirect. |
| **A4** | **High** | `ecs.tf:35-40, 98-105` | `INTERNAL_SECRET` is **never injected**, so F2 (gateway↔service trust) is off — and the ecs-tasks SG `self` rule lets any task hit any other task directly. Header spoofing / cross-tenant access between tasks. | Inject `INTERNAL_SECRET` (Secrets Manager) into the gateway + every service; tighten the self SG to only what's needed. |
| **A5** | **High** | `build-service.yml:46-48` | `mvn test` is `continue-on-error: true` — **failing tests don't block deploy**. | Make tests a gate (drop `continue-on-error`); fail the pipeline on test failure. |
| **A6** | **High** | `build-service.yml:61-82` | Builds a `:sha` image but the deploy `force-new-deployment` pulls **`:latest`** (task def pinned to `:latest`) → non-immutable deploy, no clean rollback, build/deploy race. | Render the task def with the **`:sha`** tag, register a new revision, `update-service` to it. |
| **A7** | **High** | `ci-cd.yml` / `build-service.yml` | **Long-lived AWS access keys** in GH secrets. | Switch to **GitHub OIDC** → assume a scoped AWS role (no static keys). |
| **A8** | **Med** | `ecs.tf:70-73` | Task execution role uses AWS-managed **`SecretsManagerReadWrite`** (write + all secrets). | Scoped read-only policy to the specific secret ARNs (least privilege). |
| **A9** | **Med** | `ecr.tf:8` | `image_tag_mutability = "MUTABLE"` — tags can be overwritten. | `IMMUTABLE` (pairs with A6). |
| **A10** | **Med** | `*/Dockerfile` | Containers run as **root** (no `USER`). | Add a non-root user; run the JVM as it. |
| **A11** | **Med** | pipeline | No **dependency-CVE scan** (F17) / image-scan gate / SAST. | Add OWASP dependency-check or Trivy (fs + image); gate on ECR scan findings. |
| **A12** | **Med** | `ci-cd.yml:117-119` | Prod deploy on push to master/main with **no approval gate**. | GitHub **Environment** protection (required reviewers) for prod. |
| **A13** | **Med** | `ecs.tf:99` | DB-name derivation `myplusdb_${name}` → `myplusdb_business`, but business-service uses **`myplusdb`**; and the **monolith isn't deployed** by this Terraform at all. | Map DB names explicitly per service; decide monolith deployment (ECS service or separate). |
| **A14** | **Low** | `vpc.tf:30` | Single NAT gateway (one AZ) — egress SPOF. | One NAT per AZ for HA (cost trade-off). |
| **A15** | **Low** | `ci-cd.yml:160-166` | `notify-success` is a stub echo; no deploy verification (wait-for-stable). | `aws ecs wait services-stable` + real notification. |

## Remediation order (pre-go-live)
1. **Secrets** — A1, A2, A4, A8 (Secrets Manager + ECS `secrets` + scoped role + INTERNAL_SECRET). One coherent change.
2. **Deploy integrity** — A5, A6, A9 (tests gate, immutable `:sha` deploys).
3. **Edge security** — A3 (TLS), A7 (OIDC).
4. **Hardening** — A10, A11, A12, A13.
5. **HA/polish** — A14, A15.

## Remediation status (branch security/prod-hardening)
- ✅ **A1/A2/A4/A8** — Secrets Manager + ECS `secrets`/valueFrom + INTERNAL_SECRET injected + scoped IAM.
- ✅ **A3** — ACM cert (`api.maxtheservice.com`) + HTTPS:443 + HTTP→HTTPS redirect (Hostinger DNS manual).
- ✅ **A5/A6/A9** — tests gate; immutable `:sha` deploys (register revision + wait-stable); ECR IMMUTABLE.
- ✅ **A7** — GitHub OIDC role (build + terraform); no static keys; trust scoped to repo + branch.
- ✅ **A10** — non-root user in all 12 service Dockerfiles.
- ✅ **A11** (=F17) — Trivy image scan gates the push on CRITICAL.
- ✅ **A12** — `environment: production` on the deploy job (operator sets required reviewers).
- ✅ **A13** — business-service → `myplusdb` (fixed). **Monolith stays on the existing EC2** (placed in
  the VPC so it can reach the ALB/gateway + RDS `myplusdb`); not Fargate-ized.
- ✅ **A14** — NAT is a toggle: single (cost default) / one-per-AZ HA via `single_nat_gateway`.
- ✅ **A15** — deploy verified per service (`aws ecs wait services-stable`) + a success/failure summary job.
- ✅ **Monolith-on-EC2** documented (`docs/monolith-ec2-deployment.md`) + optional RDS ingress rule
  (`monolith-ec2.tf`, `var.monolith_security_group_id`).

## Cost (us-east-1 estimate, 24/7)

The Terraform now defaults to a **cost-optimized** shape via toggles (flip to HA for prod):

| Toggle (variable) | Cost default | Prod/HA |
|---|---|---|
| `use_fargate_spot` | `true` (Spot, ~70% off compute) | `false` |
| `multi_az` (RDS) | `false` (single-AZ) | `true` |
| `enable_container_insights` | `false` | `true` |
| api-gateway / auth `desired_count` | `1` | `2` |

| | Cost-optimized default | HA (all toggles on, counts=2) |
|---|---:|---:|
| Fargate (15→13 tasks) | ~$70 (Spot) | ~$215 |
| RDS db.t3.medium | ~$60 (single-AZ) | ~$110 |
| ALB + NAT + Secrets/ECR | ~$60 | ~$77 |
| CloudWatch | ~$8 | ~$25 |
| **≈ total** | **~$200 / mo** | **~$430 / mo** |

EC2-only alternative (one `t3.large` docker-compose + RDS) ≈ $60 + DB — cheapest, but no HA / no
rolling deploys / manual ops. The monolith reuses your existing EC2 either way.

## Operator bootstrap checklist (one-time, before the pipeline runs)
1. Create the S3 backend bucket `myplus-terraform-state` (us-east-1).
2. Create a broad **terraform-deploy** IAM role with the GitHub OIDC trust (repo + branch) — used by
   `infra-deploy.yml` as `AWS_TERRAFORM_ROLE_ARN`. (Or run the first `terraform apply` locally with admin creds.)
3. `terraform apply` → creates the OIDC provider, the **build** deploy role (output `github_deploy_role_arn`),
   Secrets Manager secrets, VPC/RDS/ECS/ALB/ACM. Apply pauses on cert validation — add the
   `acm_validation_record` CNAME at Hostinger.
4. Set GitHub **secrets**: `AWS_DEPLOY_ROLE_ARN`, `AWS_TERRAFORM_ROLE_ARN`, `AWS_ACCOUNT_ID`,
   `AWS_REGION`, `DB_PASSWORD`, `JWT_SECRET`, `INTERNAL_SECRET`.
5. Configure the GitHub **`production` Environment** (required reviewers) for the deploy gate (A12).
6. Add the `api.maxtheservice.com` → ALB CNAME at Hostinger (output `app_dns_cname`).
7. Push to `security/prod-hardening` → pipeline builds, scans, deploys.

## Decisions needed from the operator (AWS-side)
- A domain + **ACM certificate** for A3 (or accept ALB DNS over HTTPS with a self-signed/wildcard).
- An **OIDC IAM role** + GitHub→AWS trust for A7.
- **Secrets Manager** secret names/layout (e.g. `myplus/prod/db_password`, `/jwt_secret`, `/internal_secret`).
- Monolith deployment target (A13) — is the monolith going to ECS too, or stay elsewhere?
