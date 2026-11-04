import re
import secrets
from datetime import UTC, datetime, timedelta
from functools import lru_cache
from pathlib import Path

import jwt
from fastapi import Depends, HTTPException, Request, Response, status

from .config import Settings, get_settings
from .models import Identity

AUTH_COOKIE = "oc_auth"
HANDOFF_PARAM = "oc_handoff"
HANDOFF_TTL = timedelta(seconds=60)
LOGIN_AUDIENCE = "opencode-manager"
HANDOFF_AUDIENCE = "opencode-manager-handoff"
USERNAME = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")


@lru_cache
def signing_key() -> str:
    settings = get_settings()
    if settings.secret_key:
        return settings.secret_key
    path = Path(settings.database_path).parent / "signing.key"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(secrets.token_urlsafe(48))
        path.chmod(0o600)
    return path.read_text().strip()


def _issue(identity: Identity, audience: str, ttl: timedelta, **claims) -> str:
    payload = {
        **claims,
        "sub": identity.sub,
        "email": identity.email,
        "name": identity.name,
        "aud": audience,
        "exp": datetime.now(UTC) + ttl,
    }
    return jwt.encode(payload, signing_key(), algorithm="HS256")


def _read(token: str, audience: str) -> dict | None:
    try:
        return jwt.decode(token, signing_key(), algorithms=["HS256"], audience=audience)
    except jwt.PyJWTError:
        return None


def _identity(claims: dict, settings: Settings) -> Identity:
    sub = claims["sub"]
    return Identity(
        sub=sub,
        email=claims.get("email") or f"{sub}@{settings.email_domain}",
        name=claims.get("name") or claims.get("preferred_username") or sub,
    )


def login_identity(username: str, settings: Settings) -> Identity:
    sub = username.strip().lower()
    if not USERNAME.match(sub):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "Invalid username")
    return Identity(sub=sub, email=f"{sub}@{settings.email_domain}", name=username.strip())


def set_auth_cookie(response: Response, identity: Identity, settings: Settings) -> None:
    ttl = timedelta(hours=settings.auth_ttl_hours)
    response.set_cookie(
        AUTH_COOKIE,
        _issue(identity, LOGIN_AUDIENCE, ttl),
        max_age=int(ttl.total_seconds()),
        httponly=True,
        samesite="lax",
        secure=settings.public_scheme == "https",
    )


def clear_auth_cookie(response: Response) -> None:
    response.delete_cookie(AUTH_COOKIE)


def handoff_token(identity: Identity, session_id: str) -> str:
    return _issue(identity, HANDOFF_AUDIENCE, HANDOFF_TTL, sid=session_id)


def read_handoff(token: str, session_id: str, settings: Settings) -> Identity | None:
    claims = _read(token, HANDOFF_AUDIENCE)
    if claims is None or claims.get("sid") != session_id:
        return None
    return _identity(claims, settings)


@lru_cache
def jwks_client(url: str) -> jwt.PyJWKClient:
    return jwt.PyJWKClient(url)


def _external_claims(request: Request, settings: Settings) -> dict | None:
    header = request.headers.get("authorization", "")
    token = header[7:] if header.lower().startswith("bearer ") else request.cookies.get(settings.jwt_cookie)
    if not token:
        return None
    if settings.jwt_jwks_url:
        key = jwks_client(settings.jwt_jwks_url).get_signing_key_from_jwt(token).key
    elif settings.jwt_secret:
        key = settings.jwt_secret
    else:
        raise RuntimeError("JWT auth requires MANAGER_JWT_JWKS_URL or MANAGER_JWT_SECRET")
    try:
        return jwt.decode(
            token,
            key,
            algorithms=settings.jwt_algorithms,
            audience=settings.jwt_audience,
            options={"verify_aud": settings.jwt_audience is not None},
        )
    except jwt.PyJWTError:
        return None


def resolve_identity(request: Request, settings: Settings) -> Identity | None:
    token = request.cookies.get(AUTH_COOKIE)
    claims = _read(token, LOGIN_AUDIENCE) if token else None
    if claims is None and settings.auth_mode == "jwt":
        claims = _external_claims(request, settings)
    return _identity(claims, settings) if claims else None


def current_identity(request: Request, settings: Settings = Depends(get_settings)) -> Identity:
    identity = resolve_identity(request, settings)
    if identity is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not authenticated")
    return identity
