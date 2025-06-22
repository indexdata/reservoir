FROM ghcr.io/graalvm/native-image-community:24.0.1

RUN microdnf install -y maven

WORKDIR /app

COPY .git/ .git
COPY pom.xml .
COPY checkstyle/ checkstyle
COPY client/ client
COPY descriptors/ descriptors
COPY js/ js
COPY server/ server
COPY util/ util
COPY xsl/ xsl

RUN mvn -DskipTests -Pnative package

ENTRYPOINT ["/bin/sh"]