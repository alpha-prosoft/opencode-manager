"""Credential values: encrypted at rest, resolved per user, never handed to sandboxes."""

import base64
import json
import logging
import os
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

import httpx
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from ..config import Settings
from ..db import Database
from .spec import CredentialSpec, PlatformSpec

log = logging.getLogger(__name__)

PLATFORM_OWNER = ""


def now() -> str:
    return datetime.now(UTC).isoformat()


def _master_key(settings: Settings) -> bytes:
    if settings.credentials_key:
        key = base64.b64decode(settings.credentials_key)
    else:
        path = Path(settings.database_path).parent / "credentials.key"
        if not path.exists():
            log.warning("MANAGER_CREDENTIALS_KEY is not set; generated %s. Set the key explicitly in production.", path)
            path.write_bytes(base64.b64encode(AESGCM.generate_key(bit_length=256)))
            path.chmod(0o600)
        key = base64.b64decode(path.read_bytes())
    if len(key) != 32:
        raise ValueError("MANAGER_CREDENTIALS_KEY must be 32 bytes, base64 encoded")
    return key


def hint(value: dict) -> str:
    secret = value.get("secret", "")
    tail = secret.strip()[-4:] if len(secret.strip()) >= 12 else ""
    return f"{value['username']} · …{tail}" if value.get("username") else (f"…{tail}" if tail else "set")


class CredentialVault:
    """Encrypted values in the manager database. Each row is bound to its credential and owner."""

    def __init__(self, settings: Settings, db: Database):
        self.db = db
        self.cipher = AESGCM(_master_key(settings))

    def _aad(self, credential_id: str, owner: str) -> bytes:
        return f"{credential_id}\0{owner}".encode()

    def put(self, credential_id: str, owner: str, value: dict, actor: str) -> None:
        nonce = os.urandom(12)
        sealed = self.cipher.encrypt(nonce, json.dumps(value).encode(), self._aad(credential_id, owner))
        with self.db.connect() as db:
            db.execute(
                "INSERT INTO credential_values (credential_id, owner, nonce, ciphertext, hint, updated_at, updated_by, verified_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, NULL) ON CONFLICT (credential_id, owner) DO UPDATE SET nonce = excluded.nonce, "
                "ciphertext = excluded.ciphertext, hint = excluded.hint, updated_at = excluded.updated_at, "
                "updated_by = excluded.updated_by, verified_at = NULL",
                (credential_id, owner, nonce, sealed, hint(value), now(), actor),
            )

    def get(self, credential_id: str, owner: str) -> dict | None:
        with self.db.connect() as db:
            row = db.execute(
                "SELECT nonce, ciphertext FROM credential_values WHERE credential_id = ? AND owner = ?", (credential_id, owner)
            ).fetchone()
        if row is None:
            return None
        return json.loads(self.cipher.decrypt(row["nonce"], row["ciphertext"], self._aad(credential_id, owner)))

    def delete(self, credential_id: str, owner: str) -> None:
        with self.db.connect() as db:
            db.execute("DELETE FROM credential_values WHERE credential_id = ? AND owner = ?", (credential_id, owner))

    def has(self, credential_id: str, owner: str) -> bool:
        with self.db.connect() as db:
            row = db.execute("SELECT 1 FROM credential_values WHERE credential_id = ? AND owner = ?", (credential_id, owner)).fetchone()
        return row is not None

    def mark_verified(self, credential_id: str, owner: str) -> None:
        with self.db.connect() as db:
            db.execute("UPDATE credential_values SET verified_at = ? WHERE credential_id = ? AND owner = ?", (now(), credential_id, owner))

    def metadata(self, owner: str | None = None) -> list[dict]:
        query, params = "SELECT credential_id, owner, hint, updated_at, updated_by, verified_at FROM credential_values", ()
        if owner is not None:
            query, params = query + " WHERE owner = ?", (owner,)
        with self.db.connect() as db:
            return [dict(row) for row in db.execute(query, params)]


@dataclass
class Resolved:
    value: dict
    source: str

    @property
    def secret(self) -> str:
        return self.value["secret"]


class Credentials:
    """Resolves a credential for a user: their own value when allowed, otherwise the platform value."""

    def __init__(self, vault: CredentialVault, secrets, platform: callable):
        self.vault = vault
        self.secrets = secrets
        self.platform = platform

    def platform_value(self, credential_id: str) -> dict | None:
        value = self.vault.get(credential_id, PLATFORM_OWNER)
        if value is not None:
            return value
        legacy = self.secrets.file_value(credential_id)
        return {"secret": legacy} if legacy else None

    def resolve(self, credential_id: str, user: str | None) -> Resolved | None:
        spec = self.platform().credential(credential_id)
        if user and spec and spec.personal:
            own = self.vault.get(credential_id, user)
            if own is not None:
                return Resolved(own, "user")
        if spec and spec.provided_by == "user":
            return None
        value = self.platform_value(credential_id)
        return Resolved(value, "platform") if value else None

    def uses_personal(self, owner: str, mcp_servers: list[str]) -> bool:
        spec = self.platform()
        for server_id in mcp_servers:
            server = spec.mcp_server(server_id)
            declared = spec.credential(server.credential) if server and server.credential else None
            if declared and declared.personal and self.vault.has(declared.id, owner):
                return True
        return False

    def for_user(self, user: str | None) -> "UserSecrets":
        return UserSecrets(self, user)

    def relevant(self, spec: PlatformSpec, teams: set[str]) -> list[CredentialSpec]:
        return [c for c in spec.credentials if c.personal and (c.teams == "*" or set(c.teams) & teams)]

    def status(self, user: str, teams: set[str]) -> list[dict]:
        spec = self.platform()
        own = {row["credential_id"]: row for row in self.vault.metadata(user)}
        items = []
        for credential in self.relevant(spec, teams):
            mine = own.get(credential.id)
            shared = credential.provided_by == "platform_or_user" and self.platform_value(credential.id) is not None
            state = "own" if mine else "platform" if shared else "missing"
            items.append(
                {
                    **credential.model_dump(exclude={"teams"}),
                    "state": state,
                    "required": credential.provided_by == "user",
                    "hint": mine["hint"] if mine else None,
                    "updated_at": mine["updated_at"] if mine else None,
                    "verified_at": mine["verified_at"] if mine else None,
                }
            )
        return items

    def test(self, spec: CredentialSpec, value: dict) -> tuple[bool, str]:
        if spec.test is None:
            return True, "Saved. This credential has no automatic check."
        try:
            response = httpx.get(spec.test.url, headers={"Authorization": authorization("bearer" if spec.kind == "token" else "basic", value)}, timeout=15)
        except httpx.HTTPError as error:
            return False, f"Could not reach {spec.test.url}: {error}"
        if response.is_success:
            return True, "Checked: the service accepted it."
        return False, f"The service rejected it (HTTP {response.status_code})."


class UserSecrets:
    """The `secrets` object handed to run handlers: resolves for the run's user, platform values otherwise."""

    def __init__(self, credentials: Credentials, user: str | None):
        self.credentials = credentials
        self.user = user

    def get(self, name: str) -> str | None:
        resolved = self.credentials.resolve(name, self.user)
        return resolved.secret if resolved else None

    def require(self, name: str) -> str:
        value = self.get(name)
        if not value:
            who = f" for {self.user}" if self.user else ""
            raise LookupError(f"Credential {name} is not set up{who}")
        return value


def authorization(scheme: str, value: dict) -> str:
    if scheme == "basic":
        pair = f"{value.get('username', '')}:{value['secret']}".encode()
        return "Basic " + base64.b64encode(pair).decode()
    if scheme == "raw":
        return value["secret"]
    return f"Bearer {value['secret']}"
