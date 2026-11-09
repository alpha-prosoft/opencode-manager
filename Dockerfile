# Build with the edd-core checkout as a named context:
#   docker build --build-context edd-core=../edd-core -t opencode-platform/manager .
# (compose.yml passes it as additional_contexts.)

FROM clojure:temurin-21-tools-deps AS web
RUN apt-get update && apt-get install -y --no-install-recommends nodejs npm && rm -rf /var/lib/apt/lists/*
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund && npm run fonts
COPY web/deps.edn web/shadow-cljs.edn ./
RUN clojure -A:shadow-cljs -P
COPY web/src src
COPY web/www www
RUN npm run fonts && clojure -M:shadow-cljs release app

FROM clojure:temurin-21-tools-deps AS service
COPY --from=edd-core . /build/edd-core
WORKDIR /build/opencode-manager/service
COPY service/deps.edn service/build.clj ./
RUN clojure -P && clojure -T:uberjar -P 2>/dev/null || true
COPY service/src src
COPY service/resources resources
RUN clojure -T:uberjar uber

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=service /build/opencode-manager/service/target/manager-svc.jar manager-svc.jar
COPY --from=web /build/web/www ui
RUN rm -rf ui/js/cljs-runtime ui/js/manifest.edn
ENV MANAGER_UI_DIST_DIR=/app/ui \
    MANAGER_PUBLIC_PORT_INTERNAL=8000 \
    MANAGER_SANDBOX_PORT=8001
EXPOSE 8000 8001
CMD ["java", "-XX:MaxRAMPercentage=75", "-Dclojure.tools.logging.factory=lambda.logging-local/slf4j-local-factory", "-jar", "manager-svc.jar"]
