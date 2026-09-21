FROM maven:3.9-eclipse-temurin-25

RUN mkdir -p /var/maven/.m2/repository && chmod -R 777 /var/maven

ENV HOME=/var/maven \
    MAVEN_CONFIG=/var/maven/.m2 \
    MAVEN_ARGS="-B -Dmaven.repo.local=/var/maven/.m2/repository"

WORKDIR /var/www
