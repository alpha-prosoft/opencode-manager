import base64

import httpx

USERNAME = "opencode"


def web_path(directory: str, conversation: str | None = None) -> str:
    encoded = base64.urlsafe_b64encode(directory.encode()).decode().rstrip("=")
    return f"/{encoded}/session/{conversation}" if conversation else f"/{encoded}"


def basic_auth(password: str) -> str:
    token = base64.b64encode(f"{USERNAME}:{password}".encode()).decode()
    return f"Basic {token}"


def is_healthy(base_url: str, password: str) -> bool:
    try:
        response = httpx.get(
            f"{base_url}/global/health", headers={"Authorization": basic_auth(password)}, timeout=2
        )
    except httpx.HTTPError:
        return False
    return response.status_code == 200 and response.json().get("healthy") is True


def session_statuses(base_url: str, password: str) -> dict[str, dict]:
    response = httpx.get(
        f"{base_url}/session/status", headers={"Authorization": basic_auth(password)}, timeout=10
    )
    response.raise_for_status()
    return response.json()


def count_busy_sessions(base_url: str, password: str) -> int:
    return sum(1 for status in session_statuses(base_url, password).values() if status.get("type") != "idle")


def _client(base_url: str, password: str) -> httpx.Client:
    return httpx.Client(base_url=base_url, headers={"Authorization": basic_auth(password)}, timeout=30)


def create_session(base_url: str, password: str, title: str) -> str:
    with _client(base_url, password) as http:
        response = http.post("/session", json={"title": title})
        response.raise_for_status()
        return response.json()["id"]


def prompt_async(base_url: str, password: str, session_id: str, model: str, text: str) -> None:
    provider_id, model_id = model.split("/", 1)
    body = {"model": {"providerID": provider_id, "modelID": model_id}, "parts": [{"type": "text", "text": text}]}
    with _client(base_url, password) as http:
        http.post(f"/session/{session_id}/prompt_async", json=body).raise_for_status()


def abort(base_url: str, password: str, session_id: str) -> None:
    with _client(base_url, password) as http:
        http.post(f"/session/{session_id}/abort").raise_for_status()


def last_reply(base_url: str, password: str, session_id: str) -> str | None:
    with _client(base_url, password) as http:
        response = http.get(f"/session/{session_id}/message")
        response.raise_for_status()
    for message in reversed(response.json()):
        info = message["info"]
        if info["role"] == "assistant" and info.get("error"):
            raise RuntimeError(f"OpenCode reported an error: {info['error']}")
        if info["role"] == "assistant" and info.get("time", {}).get("completed"):
            return "\n".join(part["text"] for part in message["parts"] if part["type"] == "text").strip()
    return None
