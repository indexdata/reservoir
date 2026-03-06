FROM --platform=linux/arm64 maven:3.9-eclipse-temurin-21 AS build_jar
WORKDIR /app

COPY pom.xml .
COPY server/pom.xml server/pom.xml
COPY util/pom.xml util/pom.xml

RUN mvn -B dependency:go-offline

COPY server/ server
COPY util/ util
COPY descriptors/ descriptors

ARG GIT_COMMIT=master
RUN echo "git.commit.id=${GIT_COMMIT}" > server/src/main/resources/git.properties

RUN mvn -B -DskipTests -Pdocker-build package

FROM --platform=linux/amd64 ghcr.io/graalvm/jdk-community:25 AS slim

ENV JAVA_MODULES=java.base,java.logging,java.sql,java.xml,java.security.sasl,jdk.graal.compiler,jdk.internal.vm.ci,jdk.unsupported,org.graalvm.truffle.compiler

RUN $JAVA_HOME/bin/jlink \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress=2 \
    --add-modules $JAVA_MODULES \
    --output /opt/graalvm-slim

FROM --platform=linux/amd64 oraclelinux:9-slim

RUN useradd -u 1000 -m -s /sbin/nologin reservoir

COPY --from=slim /opt/graalvm-slim /opt/graalvm-slim
COPY --from=build_jar /app/server/target/reservoir-server-fat.jar /reservoir.jar

RUN chown reservoir:reservoir /reservoir.jar
USER reservoir

ENV JAVA_HOME=/opt/graalvm-slim
ENV PATH="$JAVA_HOME/bin:${PATH}"
ENV HTTP_PORT=8081
ENV GRAAL_OPTS="--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED"

EXPOSE $HTTP_PORT

ENTRYPOINT ["sh", "-c", "exec $JAVA_HOME/bin/java -Dport=$HTTP_PORT $JAVA_OPTS $GRAAL_OPTS -jar /reservoir.jar"]
