FROM ghcr.io/graalvm/native-image-community:24.0.2 AS build
# Note that this is based on Red Hat Enterprise Linux release 9.5 (Plow)

RUN microdnf install -y maven

WORKDIR /app

# create runtime user
RUN useradd \
  --home-dir /nonexistent \
  --shell /sbin/nologin \
  --no-create-home \
  --uid 65532 \
  myuser

# Needed for git-commit-id-plugin
COPY .git/ .git

# Reamining files are copied for building the native image
COPY pom.xml .
COPY checkstyle/ checkstyle
COPY client/ client
COPY descriptors/ descriptors
COPY js/ js
COPY server/ server
COPY util/ util
COPY xsl/ xsl

# Twice so that artifact(s) is step 1 and cached for native image build
RUN mvn -DskipTests package
RUN mvn -DskipTests -Pnative package

# Save list of shared lib deps
RUN ldd server/target/reservoir-native | tr -s '[:blank:]' '\n' | grep '^/' | \
  xargs -I % sh -c 'mkdir -p $(dirname deps%); cp % deps%;'

RUN mkdir -p /app/tmp/vertx-cache
RUN chmod -R 777 /app/tmp
RUN chown -R myuser:myuser /app/tmp

FROM scratch

# user, group, and timezone data
COPY --from=build /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=build /etc/passwd /etc/passwd
COPY --from=build /etc/group /etc/group

COPY --from=build /app/tmp /tmp
COPY --from=build /app/server/target/reservoir-native /reservoir-native
COPY --from=build /app/client/target/client-native /client-native
COPY --from=build /app/deps /

EXPOSE 8081

# Get Exception in thread "main" java.lang.IllegalStateException: Unable to create folder at path '/tmp/vertx-cache'
# despite /tmp/vertx-cache being created with 777 permissions
# USER myuser:myuser

CMD ["/reservoir-native"]
