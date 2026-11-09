# Scripted OpenAI-compatible LLM for the documentation stack (compose.docs.yml). Plays scenarios.json; calls no model.
FROM python:3.14-slim
RUN useradd -u 1000 -M app
COPY server.py scenarios.json /app/
USER 1000
EXPOSE 8000
CMD ["python", "/app/server.py"]
