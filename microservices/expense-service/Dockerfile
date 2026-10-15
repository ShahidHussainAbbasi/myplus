FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
# EX-5 — the receipts directory belongs to the app user, so a fresh named volume mounted here is writable by it
RUN mkdir -p /data/receipts && chown app:app /data/receipts
VOLUME /tmp
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
USER app
ENTRYPOINT ["java","-jar","/app.jar"]
