import hashlib
import io
import shutil
import tarfile
import zipfile
from pathlib import Path

import httpx

from . import git

MAX_ARCHIVE_BYTES = 50 * 1024 * 1024


class SourceError(RuntimeError):
    pass


def _download(url: str, token: str | None) -> bytes:
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    with httpx.Client(timeout=60, follow_redirects=True) as http:
        with http.stream("GET", url, headers=headers) as response:
            if response.status_code >= 400:
                raise SourceError(f"download of {url} failed with HTTP {response.status_code}")
            data = bytearray()
            for chunk in response.iter_bytes():
                data += chunk
                if len(data) > MAX_ARCHIVE_BYTES:
                    raise SourceError(f"archive {url} is larger than {MAX_ARCHIVE_BYTES // 1024 // 1024} MB")
    return bytes(data)


def _extract(data: bytes, target: Path) -> None:
    if zipfile.is_zipfile(io.BytesIO(data)):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for member in archive.infolist():
                destination = (target / member.filename).resolve()
                if not destination.is_relative_to(target.resolve()):
                    raise SourceError(f"archive entry {member.filename} escapes the target directory")
            archive.extractall(target)
        return
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as archive:
            archive.extractall(target, filter="data")
    except (tarfile.TarError, OSError) as error:
        raise SourceError(f"not a valid .tar.gz or .zip archive: {error}") from error


def fetch_archive(url: str, target: Path, token: str | None) -> str:
    """Download and extract an archive into `target` and return the archive's sha256."""
    if not url.startswith("https://"):
        raise SourceError("archive sources must use https://")
    data = _download(url, token)
    digest = hashlib.sha256(data).hexdigest()
    staging = target.with_name(target.name + ".new")
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True)
    _extract(data, staging)
    entries = [p for p in staging.iterdir() if not p.name.startswith(".") or p.name == ".platform"]
    root = entries[0] if len(entries) == 1 and entries[0].is_dir() and entries[0].name != ".platform" else staging
    shutil.rmtree(target, ignore_errors=True)
    shutil.move(str(root), str(target))
    shutil.rmtree(staging, ignore_errors=True)
    return f"sha256:{digest[:12]}"


def local_directory(location: str, local_roots: list[str]) -> Path:
    path = Path(location).resolve()
    if not any(path.is_relative_to(Path(root).resolve()) for root in local_roots):
        raise SourceError(f"local paths must live under {', '.join(local_roots) or 'an allowed root (none configured)'}")
    if not path.is_dir():
        raise SourceError(f"{location} is not a directory")
    return path


def fingerprint(directory: Path) -> str:
    digest = hashlib.sha256()
    for file in sorted(p for p in directory.rglob("*") if p.is_file() and ".git" not in p.parts):
        digest.update(str(file.relative_to(directory)).encode())
        digest.update(file.read_bytes())
    return f"files:{digest.hexdigest()[:12]}"


def is_git_url(url: str) -> bool:
    return url.startswith(("https://", "file://", "ssh://")) or git.is_ssh(url)
