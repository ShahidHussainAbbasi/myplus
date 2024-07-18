FROM adoptopenjdk/openjdk8:alpine-slim
VOLUME /tmp
COPY target/myplus.war myplus.war
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/myplus.war"]