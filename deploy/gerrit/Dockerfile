# One-shot setup of the local Gerrit (compose.ci.yml): accounts, HTTP passwords and the seeded demo project.
FROM python:3.14-slim
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* && useradd -u 1000 -m app
COPY init.py /app/init.py
USER 1000
CMD ["python", "/app/init.py"]
