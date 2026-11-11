#!/usr/bin/env python3
"""Checks the documentation against the product it describes.

  * every `helm install` / `helm upgrade` in the docs renders with
    `helm template` and the same flags, and the chart passes `helm lint`
  * every Helm value the docs name exists in the chart's values.yaml
  * every MANAGER_* setting the docs name exists in config.clj, and every
    setting of config.clj is documented in reference/settings.md
  * every JSON code block parses

Run from the repository root: scripts/check-docs.py (or `make docs-check`).
"""
import json
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
CHART = ROOT / "charts" / "opencode-manager"
CONFIG = ROOT / "service" / "src" / "manager_svc" / "config.clj"
SETTINGS_PAGE = DOCS / "reference" / "settings.md"
EXCLUDED = {DOCS / "test-coverage.md"}

FENCE = re.compile(r"^```(\w*)\n(.*?)^```", re.M | re.S)
BACKTICKED = re.compile(r"`([^`\n]+)`")
VALUE_PATH = re.compile(r"[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9-]*)+|[A-Za-z][A-Za-z0-9]*")
SETTING = re.compile(r"\bMANAGER_[A-Z0-9_]+\b")
PLACEHOLDER = re.compile(r"MANAGER_(?:<MODE>|JWT|OIDC)_")
NOT_SETTINGS = {"MANAGER_URL"}

problems = []


def problem(page, message):
    problems.append(f"{page.relative_to(ROOT)}: {message}")


def pages():
    return [p for p in sorted(DOCS.rglob("*.md")) if p not in EXCLUDED]


def code_blocks(text):
    return [(m.group(1), m.group(2)) for m in FENCE.finditer(text)]


def commands(block):
    joined = re.sub(r"\\\n\s*", " ", block)
    return [line.strip() for line in joined.splitlines() if line.strip() and not line.strip().startswith("#")]


def helm_commands(text):
    found = []
    for language, block in code_blocks(text):
        if language in ("bash", "sh", "shell", "console", ""):
            for command in commands(block):
                words = shlex.split(command)
                if len(words) > 2 and words[0] == "helm" and words[1] in ("install", "upgrade"):
                    found.append(words)
    return found


def template_arguments(words):
    """The flags of a documented install or upgrade that decide what is
    rendered; --reuse-values has nothing to reuse in a dry render."""
    arguments = []
    index = 4
    while index < len(words):
        word = words[index]
        if word in ("--set", "--set-json", "--set-string", "-f", "--values", "-n", "--namespace"):
            arguments += [word, words[index + 1]]
            index += 2
        elif word.startswith(("--set=", "--set-json=", "--set-string=")):
            arguments.append(word)
            index += 1
        else:
            index += 1
    return arguments


def set_keys(words):
    keys = []
    for index, word in enumerate(words):
        if index + 1 == len(words):
            break
        if word == "--set-json":
            keys.append(words[index + 1].split("=", 1)[0])
        elif word in ("--set", "--set-string"):
            keys += [pair.split("=", 1)[0] for pair in words[index + 1].split(",")]
    return keys


def resolves(values, path):
    """True when a dotted path names a value of the chart. A free-form map
    or a list (an empty default) accepts anything below it."""
    node = values
    for part in path.split("."):
        part = re.sub(r"\[\d*\]$", "", part)
        if isinstance(node, dict) and part in node:
            node = node[part]
        elif isinstance(node, dict) and not node:
            return True
        elif isinstance(node, list) or node is None:
            return True
        else:
            return False
    return True


def run(arguments):
    return subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True)


def build_dependencies():
    """Fetches the chart's dependencies; helm only does that from repositories
    it has been told about."""
    chart = yaml.safe_load((CHART / "Chart.yaml").read_text())
    for dependency in chart.get("dependencies", []):
        run(["helm", "repo", "add", "--force-update", dependency["name"], dependency["repository"]])
    return run(["helm", "dependency", "build", str(CHART)])


def check_helm(values):
    if not shutil.which("helm"):
        problems.append("helm is not installed: the documented Helm commands were not rendered")
        return
    if not any((CHART / "charts").glob("*.tgz")):
        build = build_dependencies()
        if build.returncode != 0:
            problems.append("helm dependency build failed: " + build.stderr.strip()[-400:])
            return
    lint = run(["helm", "lint", str(CHART)])
    if lint.returncode != 0:
        problems.append("helm lint failed: " + (lint.stdout + lint.stderr).strip()[-600:])
    rendered = 0
    for page in pages():
        for words in helm_commands(page.read_text()):
            for key in set_keys(words):
                if not resolves(values, key):
                    problem(page, f"`helm {words[1]}` sets `{key}`, which the chart doesn't have")
            if words[1] == "upgrade" and "--reuse-values" in words and not set_keys(words):
                continue
            result = run(["helm", "template", words[2], str(CHART)] + template_arguments(words))
            rendered += 1
            if result.returncode != 0:
                problem(page, f"`{' '.join(words[:4])} …` doesn't render: " + result.stderr.strip()[-600:])
    if rendered == 0:
        problems.append("no `helm install` found in the docs to render")


def helm_pages():
    """Pages that name Helm values; the JSON field references use the same
    dotted style for other things."""
    return sorted(DOCS.glob("story/*.md")) + [SETTINGS_PAGE, DOCS / "reference" / "security.md"]


def check_helm_values(values):
    top_level = set(values)
    for page in helm_pages():
        text = page.read_text()
        for token in BACKTICKED.findall(text):
            for candidate in token.replace("=", " ").split():
                candidate = candidate.strip(",;:()")
                if not VALUE_PATH.fullmatch(candidate) or "." not in candidate:
                    continue
                if candidate.split(".")[0] in top_level and not resolves(values, candidate):
                    problem(page, f"Helm value `{candidate}` isn't in the chart's values.yaml")


def known_settings():
    """The settings config.clj reads: MANAGER_ and the upper-cased key of
    each entry of its defaults map."""
    text = CONFIG.read_text()
    start = text.index("(def defaults")
    block = text[start:text.index("})", start)]
    keys = re.findall(r"^\s*\{?:([a-z0-9-]+)\s", block, re.M)
    return {"MANAGER_" + key.upper().replace("-", "_") for key in keys}


def check_settings():
    known = known_settings()
    for page in pages():
        for name in set(SETTING.findall(PLACEHOLDER.sub("MANAGER_X_", page.read_text()))):
            if name.startswith("MANAGER_X_") or name in NOT_SETTINGS:
                continue
            if name not in known:
                problem(page, f"setting `{name}` isn't in config.clj")
    documented = set(SETTING.findall(SETTINGS_PAGE.read_text()))
    mode_settings = {re.sub(r"^MANAGER_(JWT|OIDC)_", "MANAGER_<MODE>_", name) for name in known}
    page_text = SETTINGS_PAGE.read_text()
    for name in sorted(known):
        generic = re.sub(r"^MANAGER_(JWT|OIDC)_", "MANAGER_<MODE>_", name)
        if name not in documented and not (generic in mode_settings and generic in page_text):
            problem(SETTINGS_PAGE, f"setting `{name}` of config.clj isn't documented")


def parses(block):
    """True for a JSON document, or for a fragment of one: the fields of an
    object without its braces."""
    for candidate in (block, "{" + block + "}"):
        try:
            json.loads(candidate)
            return True
        except json.JSONDecodeError:
            pass
    return False


def check_json():
    for page in pages():
        for language, block in code_blocks(page.read_text()):
            if language == "json" and not parses(block):
                problem(page, f"a json block doesn't parse: {block.strip()[:60]!r}")


def main():
    values = yaml.safe_load((CHART / "values.yaml").read_text())
    check_helm(values)
    check_helm_values(values)
    check_settings()
    check_json()
    if problems:
        print(f"{len(problems)} problem(s) in the documentation:")
        for line in problems:
            print("  -", line)
        return 1
    print("The documentation matches the chart, the settings and its own JSON.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
