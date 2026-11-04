import base64

import httpx

USERNAME = "opencode"


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


def count_busy_sessions(base_url: str, password: str) -> int:
    response = httpx.get(
        f"{base_url}/session/status", headers={"Authorization": basic_auth(password)}, timeout=10
    )
    response.raise_for_status()
    return sum(1 for status in response.json().values() if status.get("type") != "idle")
