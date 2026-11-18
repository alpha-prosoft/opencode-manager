# A mock MCP server for tests and demos: it says back which credential the platform's gateway called it with.
FROM python:3.14-slim
RUN pip install --no-cache-dir mcp==2.3.0 uvicorn==0.54.0 && useradd -u 1000 -M app
COPY server.py /app/server.py
USER 1000
EXPOSE 8000
CMD ["python", "/app/server.py"]
