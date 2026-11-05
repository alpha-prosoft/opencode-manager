# OpenCode with common toolchains so agents can build and test code.
ARG OPENCODE_VERSION=1.19.5
FROM ghcr.io/anomalyco/opencode:${OPENCODE_VERSION}
RUN apk add --no-cache git bash make python3 py3-pip nodejs npm ripgrep jq \
    && adduser -D -u 1000 -h /session/home agent
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PYTHONDONTWRITEBYTECODE=1 npm_config_update_notifier=false
