from functools import lru_cache

import jwt
from fastapi import Depends, HTTPException, Request, status

from .config import Settings, get_settings
from .models import Identity


@lru_cache
def jwks_client(url: str) -> jwt.PyJWKClient:
    return jwt.PyJWKClient(url)


def extract_token(request: Request, settings: Settings) -> str | None:
    header = request.headers.get("authorization", "")
    if header.lower().startswith("bearer "):
        return header[7:]
    return request.cookies.get(settings.jwt_cookie)


def decode_token(token: str, settings: Settings) -> dict:
    if settings.jwt_jwks_url:
        key = jwks_client(settings.jwt_jwks_url).get_signing_key_from_jwt(token).key
    elif settings.jwt_secret:
        key = settings.jwt_secret
    else:
        raise RuntimeError("JWT auth requires MANAGER_JWT_JWKS_URL or MANAGER_JWT_SECRET")
    return jwt.decode(
        token,
        key,
        algorithms=settings.jwt_algorithms,
        audience=settings.jwt_audience,
        options={"verify_aud": settings.jwt_audience is not None},
    )


def resolve_identity(request: Request, settings: Settings) -> Identity | None:
    if settings.auth_mode == "dev":
        return Identity(sub=settings.dev_sub, email=settings.dev_email, name=settings.dev_sub)

    token = extract_token(request, settings)
    if not token:
        return None
    try:
        claims = decode_token(token, settings)
    except jwt.PyJWTError:
        return None
    sub = claims["sub"]
    return Identity(
        sub=sub,
        email=claims.get("email") or f"{sub}@{settings.email_domain}",
        name=claims.get("name") or claims.get("preferred_username"),
    )


def current_identity(request: Request, settings: Settings = Depends(get_settings)) -> Identity:
    identity = resolve_identity(request, settings)
    if identity is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not authenticated")
    return identity
