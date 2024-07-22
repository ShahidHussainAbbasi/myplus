#Maven Build
FROM maven:3.8.3-openjdk-8 AS builder
COPY pom.xml /myplus/
COPY src /myplus/src
RUN --mount=type=cache,target=/root/.m2 mvn -f /app/pom.xml clean package -DskipTests

#Run
FROM openjdk:8-jre
COPY --from=builder /myplus/target/myplus-0.0.1-SNAPSHOT.jar myplus.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "myplus.jar"]