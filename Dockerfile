# Local image of mds for docker-compose.yml (README "In Docker"): the official Eclipse Temurin 21 JRE
# image with the jar that Gradle builds on the host. Nothing is downloaded while building but the
# base image, so it works wherever Docker can pull images, whichever runtime (Docker Desktop,
# Rancher Desktop, Colima) and whatever proxy. Build the jar first:
#
#   ./gradlew bootJar && docker compose --profile app build mds     (or: scripts/dev.sh docker)
#
# Production images are built with buildpacks (docs/deployment.md "Container images").

FROM eclipse-temurin:21-jre-noble AS layers
WORKDIR /layers
COPY build/libs/mds.jar mds.jar
# Spring Boot's layers (dependencies, loader, snapshots, application), so a code change rebuilds
# only the last one
RUN java -Djarmode=tools -jar mds.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-noble
# the buildpack image's user and group, so files and volumes behave the same
RUN groupadd --system --gid 1001 mds && useradd --system --uid 1002 --gid 1001 --no-create-home mds
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER 1002:1001
EXPOSE 8080 9090 5701
# Heap from the container's memory limit (docker-compose.yml sets 1.5 GiB: about 890 MiB), leaving
# room for metaspace, threads and 192 MiB of direct memory for gRPC, as in deploy/. java is PID 1,
# so SIGTERM starts Spring's graceful shutdown. More JVM options: JAVA_TOOL_OPTIONS in config/docker.env.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:MaxDirectMemorySize=192m", "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]
