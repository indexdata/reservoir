# https://github.com/folio-org/folio-tools/tree/master/folio-java-docker/openjdk21
FROM folioci/alpine-jre-openjdk21:latest

# Install latest patch versions of packages: https://pythonspeed.com/articles/security-updates-in-docker/
USER root
RUN apk upgrade --no-cache
USER folio

ENV VERTICLE_FILE mod-reservoir-server-fat.jar

# Set the location of the verticles
ENV VERTICLE_HOME /usr/verticles

# Copy your fat jar to the container
COPY server/target/${VERTICLE_FILE} ${VERTICLE_HOME}/${VERTICLE_FILE}

COPY server/target/compiler/*.jar ${VERTICLE_HOME}/compiler/

# Expose this port locally in the container.
EXPOSE 8081

ENV JAVA_OPTIONS "--upgrade-module-path=compiler -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI"
