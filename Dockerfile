# FROM adoptopenjdk/openjdk8:alpine-slim
# VOLUME /tmp
# COPY target/jar.war jar.war
# ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/myplus.jar"]

# WORKDIR /bezkoder-app
# COPY . .
# RUN mvn clean install

# CMD mvn spring-boot:run

#Maven Build
FROM maven:3.8.3-openjdk-8 AS builder
COPY pom.xml /myplus/
COPY src /myplus/src
RUN --mount=type=cache,target=/root/.m2 mvn -f /myplus/pom.xml clean package -DskipTests

#Run
FROM openjdk:8-jre
COPY --from=builder /myplus/target/myplus.jar myplus.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "myplus.jar"]