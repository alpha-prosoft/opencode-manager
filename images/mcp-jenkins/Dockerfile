# Read-only Jenkins MCP server (set JENKINS_URL). Holds no credentials; the platform gateway adds them per request.
FROM python:3.14-slim
RUN pip install --no-cache-dir mcp==2.3.0 httpx==0.28.1 uvicorn==0.54.0 && useradd -u 1000 -M app
COPY server.py /app/server.py
USER 1000
EXPOSE 8000
CMD ["python", "/app/server.py"]
