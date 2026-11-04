import httpx


def is_healthy(base_url: str) -> bool:
    try:
        return httpx.get(f"{base_url}/health", timeout=2).status_code == 200
    except httpx.HTTPError:
        return False


def count_active_tasks(base_url: str, email: str) -> int:
    with httpx.Client(base_url=base_url, timeout=10) as http:
        signin = http.post(
            "/api/v1/auths/signin",
            headers={"X-Forwarded-Email": email},
            json={"email": email, "password": ""},
        )
        signin.raise_for_status()
        token = signin.json()["token"]
        tasks = http.get("/api/tasks", headers={"Authorization": f"Bearer {token}"})
        tasks.raise_for_status()
        return len(tasks.json()["tasks"])
