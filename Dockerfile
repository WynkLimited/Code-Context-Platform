FROM ghcr.io/sourcegraph/zoekt:0.0.0-20261026142308-e8242d26df19 AS zoekt-tools
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends git python3 python3-venv ca-certificates gosu \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/graphify && /opt/graphify/bin/pip install --no-cache-dir 'graphifyy[ollama]==0.9.118' \
    && groupadd --system --gid 10001 context && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent context \
    && mkdir -p /app /var/lib/code-context/repos /var/lib/code-context/zoekt /fixtures \
    && chown -R 10001:10001 /app /var/lib/code-context
COPY --from=build /build/target/code-context-platform-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=zoekt-tools /usr/local/bin/zoekt-git-index /usr/local/bin/zoekt-git-index
COPY --from=zoekt-tools /usr/local/bin/zoekt-local-sync /usr/local/bin/zoekt-local-sync
COPY scripts/container-entrypoint.sh /usr/local/bin/container-entrypoint.sh
RUN chmod 0755 /usr/local/bin/container-entrypoint.sh
ENV PATH="/opt/graphify/bin:/usr/local/bin:${PATH}"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/container-entrypoint.sh"]
