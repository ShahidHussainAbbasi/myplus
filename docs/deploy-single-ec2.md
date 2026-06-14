# Manual deploy — everything on one AWS EC2 (docker-compose)

Run the whole stack — **MySQL + 13 microservices + the monolith** — on a single EC2 with Docker
Compose. (The ECS/Terraform path stays in the repo for later; this is the manual route.)

## 1. EC2 sizing & OS
14 JVMs + MySQL is memory-heavy. Recommended:
- **~16 GB RAM** (e.g. `t3.xlarge` / `r5.large`), 2+ vCPU, **40–50 GB** disk (gp3).
- Amazon Linux 2023 or Ubuntu 22.04.
- **Security group:** inbound `22` (SSH, your IP), `80`/`443` (and `8080` if you hit the monolith
  directly), `8765` only if you expose the gateway publicly. Everything else stays internal to the
  Docker network — don't open 8081–8090/3306/8761/8888.

## 2. Install Docker
```bash
# Amazon Linux 2023
sudo dnf -y install docker git && sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user && newgrp docker
# docker compose plugin
sudo dnf -y install docker-compose-plugin || \
  (sudo mkdir -p /usr/libexec/docker/cli-plugins && \
   sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
     -o /usr/libexec/docker/cli-plugins/docker-compose && \
   sudo chmod +x /usr/libexec/docker/cli-plugins/docker-compose)
```

## 3. Get the code + secrets
```bash
git clone https://github.com/ShahidHussainAbbasi/myplus.git
cd myplus && git checkout security/prod-hardening
cp microservices/.env.example microservices/.env   # then edit with REAL values
```
`microservices/.env` (git-ignored) — fill in:
```
DB_USER=root
DB_PASSWORD=<strong>
JWT_SECRET=<256-bit base64>      # same value used by auth + gateway
INTERNAL_SECRET=<strong random>  # gateway<->service trust
MAIL_PASSWORD=<gmail app password>
RECAPTCHA_SECRET=<optional>
```

## 4. Build the jars (Docker-only — no Maven/JDK install needed)
```bash
# microservice jars (multi-module)
docker run --rm -v "$PWD":/ws -w /ws/microservices maven:3.9-eclipse-temurin-25 mvn -B -DskipTests package
# monolith jar -> target/myplus.jar
docker run --rm -v "$PWD":/ws -w /ws maven:3.9-eclipse-temurin-25 mvn -B -DskipTests package
```
(Or install JDK 25 + Maven and run `mvn -DskipTests package` in each.)

## 5. Bring it up
```bash
cd microservices
docker compose up -d --build      # builds images (incl. monolith from ../Dockerfile) and starts all
docker compose ps
```
Start order is handled by health-gated `depends_on` (mysql → config → eureka → gateway → services →
monolith). Give it ~2–3 min on first boot (Flyway baselines per service DB; the monolith uses
`DDL_AUTO=update`).

## 6. Verify
```bash
curl -s localhost:8765/actuator/health      # gateway
curl -s localhost:8080/login -o /dev/null -w '%{http_code}\n'   # monolith UI (200)
docker compose logs -f monolith
```
- Microservices URL (in-network): the monolith calls `http://api-gateway:8765` (set via compose).
- Users: `http://<ec2-ip>:8080` (monolith UI). Front it with nginx + Let's Encrypt or an ALB for TLS.

## 7. DNS / TLS (Hostinger)
- Point **`app.maxtheservice.com`** → the EC2 public IP (A record).
- TLS: easiest is **nginx + certbot** on the box terminating 443 → `localhost:8080` (monolith) and
  optionally `/api` → `localhost:8765` (gateway). Then the monolith's public base can be
  `https://app.maxtheservice.com`.

## 8. Memory (pre-wired)
The compose already caps every container so 14 JVMs + MySQL fit ~16 GB:
- each Java service: `mem_limit: 768m` (monolith `1024m`) + `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60.0 -Xss512k`
  (~`460 MB` heap, rest for metaspace/threads),
- `mysql: 1536m`.
- **Total ≈ 12.5 GB** of limits → leaves headroom on a 16 GB box. Bump individual `mem_limit`s if a
  service OOMKills (watch `docker compose stats` / exit code 137). For a smaller box, lower the limits
  and `MaxRAMPercentage`, or run fewer services.

## 9. Ops
```bash
docker compose pull && docker compose up -d --build   # update after `git pull` + rebuild jars
docker compose restart <service>
docker compose logs -f <service>
docker compose down                                   # stop (keeps mysql-data volume)
```

> Note: the bundled `mysql` container holds all DBs (`myplusdb`, `myplusdb_auth`, `myplusdb_education`,
> …) on the `mysql-data` volume. For durability/backups consider RDS later (point the services'
> `SPRING_DATASOURCE_URL` + the monolith `JDBC_URL` at the RDS endpoint and drop the mysql service).
