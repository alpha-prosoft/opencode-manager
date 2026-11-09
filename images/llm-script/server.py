"""A scripted, OpenAI-compatible LLM for reproducible documentation and demos.

It answers POST /v1/chat/completions (streamed or not) from scenarios.json instead of a model. A scenario is a
fixed list of assistant turns (text and tool calls with fixed arguments). The server keeps no state: the scenario
is the first one whose `match` phrases all occur in the conversation's user and tool messages, and the turn is the
number of assistant messages the conversation already has. Repeating a request therefore repeats the answer.

A scenario may name a `prelude`: turns (and phrases) it shares with others, played first; typically "read the
task file", after which the task's own phrases are in the conversation. `capture` maps names to regular
expressions over the conversation; `{{name}}` in texts and arguments is replaced by the first group found. Any
value may be {"file": "texts/x.md"}: the text of that file, relative to scenarios.json. Tool names are matched by
suffix, since clients prefix the tools of MCP servers. Conversations no scenario matches, and turns past a
scenario's end, get the `default` reply. Token usage is what the turn says.

Settings: LLM_SCRIPT_SCENARIOS (file, default next to this one; read again when it or a file beside it changes),
LLM_SCRIPT_PORT (8000), LLM_SCRIPT_CHUNK_MS (pause between stream chunks, 20), LLM_SCRIPT_TRACE_DIR (writes every
request body there, for writing scenarios).
"""

import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCENARIOS = Path(os.environ.get("LLM_SCRIPT_SCENARIOS", Path(__file__).with_name("scenarios.json")))
PORT = int(os.environ.get("LLM_SCRIPT_PORT", "8000"))
CHUNK_SECONDS = int(os.environ.get("LLM_SCRIPT_CHUNK_MS", "20")) / 1000
TRACE_DIR = os.environ.get("LLM_SCRIPT_TRACE_DIR")
MAX_BODY_BYTES = 64 * 1024 * 1024
TEXT_CHUNK = 48


def with_files(value, directory):
    if isinstance(value, dict) and set(value) == {"file"}:
        return (directory / value["file"]).read_text(encoding="utf-8")
    if isinstance(value, dict):
        return {key: with_files(item, directory) for key, item in value.items()}
    if isinstance(value, list):
        return [with_files(item, directory) for item in value]
    return value


def load(path):
    script = with_files(json.loads(Path(path).read_text(encoding="utf-8")), Path(path).parent)
    preludes = script.get("preludes", {})
    scenarios = []
    for scenario in script.get("scenarios", []):
        prelude = preludes[scenario["prelude"]] if scenario.get("prelude") else {}
        scenarios.append({
            "id": scenario["id"],
            "match": prelude.get("match", []) + scenario.get("match", []),
            "turns": prelude.get("turns", []) + scenario.get("turns", []),
            "capture": {**prelude.get("capture", {}), **scenario.get("capture", {})},
            "title": scenario.get("title"),
        })
    for name, prelude in preludes.items():
        scenarios.append({"id": f"prelude:{name}", "match": prelude.get("match", []),
                          "turns": prelude.get("turns", []), "capture": prelude.get("capture", {}), "title": None})
    return {"scenarios": scenarios, "default": script["default"], "title": script.get("title", {})}


def text_of(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(part.get("text", "") for part in content if isinstance(part, dict))
    return ""


def conversation_text(messages):
    return "\n".join(text_of(m.get("content")) for m in messages if m.get("role") in ("user", "tool"))


def select(script, text):
    for scenario in script["scenarios"]:
        if scenario["match"] and all(phrase in text for phrase in scenario["match"]):
            return scenario
    return None


def is_title_request(script, body):
    marker = script["title"].get("system_marker")
    if not marker or body.get("tools"):
        return False
    system = "\n".join(text_of(m.get("content")) for m in body.get("messages", []) if m.get("role") == "system")
    return marker in system


def captures(scenario, text):
    found = {}
    for name, pattern in scenario["capture"].items():
        match = re.search(pattern, text)
        found[name] = match.group(1) if match else ""
    return found


def filled(value, values):
    if isinstance(value, str):
        return re.sub(r"\{\{(\w+)\}\}", lambda m: values.get(m.group(1), m.group(0)), value)
    if isinstance(value, list):
        return [filled(item, values) for item in value]
    if isinstance(value, dict):
        return {key: filled(item, values) for key, item in value.items()}
    return value


def tool_name(wanted, offered):
    if wanted in offered:
        return wanted
    suffixed = [name for name in offered if name.endswith("_" + wanted)]
    return suffixed[0] if suffixed else wanted


def answer(script, body):
    """(scenario id, step, turn) for a chat completion request."""
    messages = body.get("messages", [])
    text = conversation_text(messages)
    scenario = select(script, text)
    if is_title_request(script, body):
        title = (scenario or {}).get("title") or script["title"].get("text", "Conversation")
        return "title", 0, {"text": title, "usage": script["title"].get("usage")}
    step = sum(1 for m in messages if m.get("role") == "assistant")
    if scenario is None or step >= len(scenario["turns"]):
        return (scenario or {"id": "default"})["id"], step, script["default"]
    offered = [tool.get("function", {}).get("name", "") for tool in body.get("tools") or []]
    turn = filled(scenario["turns"][step], captures(scenario, text))
    calls = [{"id": f"call_{scenario['id'].replace('-', '_')}_{step}_{index}",
              "name": tool_name(call["name"], offered),
              "arguments": json.dumps(call.get("arguments", {}), ensure_ascii=False)}
             for index, call in enumerate(turn.get("tools", []))]
    return scenario["id"], step, {**turn, "calls": calls}


def usage_of(turn, script):
    usage = turn.get("usage") or script["default"].get("usage") or {}
    prompt, completion, cached = usage.get("prompt", 0), usage.get("completion", 0), usage.get("cached", 0)
    return {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion,
            "prompt_tokens_details": {"cached_tokens": cached}}


def completion(identifier, model, turn, usage):
    message = {"role": "assistant", "content": turn.get("text") or None}
    if turn.get("calls"):
        message["tool_calls"] = [{"id": call["id"], "type": "function",
                                  "function": {"name": call["name"], "arguments": call["arguments"]}}
                                 for call in turn["calls"]]
    return {"id": identifier, "object": "chat.completion", "created": 0, "model": model,
            "choices": [{"index": 0, "message": message,
                         "finish_reason": "tool_calls" if turn.get("calls") else "stop"}],
            "usage": usage}


def chunks(identifier, model, turn, usage):
    def chunk(delta, finish=None):
        return {"id": identifier, "object": "chat.completion.chunk", "created": 0, "model": model,
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}

    yield chunk({"role": "assistant", "content": ""})
    text = turn.get("text") or ""
    for start in range(0, len(text), TEXT_CHUNK):
        yield chunk({"content": text[start:start + TEXT_CHUNK]})
    for index, call in enumerate(turn.get("calls", [])):
        yield chunk({"tool_calls": [{"index": index, "id": call["id"], "type": "function",
                                     "function": {"name": call["name"], "arguments": call["arguments"]}}]})
    yield chunk({}, "tool_calls" if turn.get("calls") else "stop")
    yield {"id": identifier, "object": "chat.completion.chunk", "created": 0, "model": model, "choices": [],
           "usage": usage}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    loaded = (None, None)
    requests = 0

    @property
    def script(self):
        modified = max(file.stat().st_mtime_ns for file in SCENARIOS.parent.rglob("*") if file.is_file())
        if Handler.loaded[0] != modified:
            Handler.loaded = (modified, load(SCENARIOS))
        return Handler.loaded[1]

    def log_message(self, *_):
        pass

    def send_json(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/healthz":
            self.send_json(200, {"status": "ok", "scenarios": len(self.script["scenarios"])})
        elif self.path == "/v1/models":
            self.send_json(200, {"object": "list", "data": []})
        else:
            self.send_json(404, {"error": {"message": "Not found"}})

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            return self.send_json(404, {"error": {"message": "Not found"}})
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY_BYTES:
            return self.send_json(413, {"error": {"message": "Request too large"}})
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw)
        except ValueError:
            return self.send_json(400, {"error": {"message": "The request must be JSON"}})
        Handler.requests += 1
        number = Handler.requests
        script = self.script
        scenario, step, turn = answer(script, body)
        model = body.get("model", "")
        usage = usage_of(turn, script)
        print(json.dumps({"request": number, "scenario": scenario, "step": step, "model": model,
                          "stream": bool(body.get("stream")), "messages": len(body.get("messages", [])),
                          "tools": [call["name"] for call in turn.get("calls", [])]}), flush=True)
        if TRACE_DIR:
            Path(TRACE_DIR, f"{number:05d}-{scenario.replace(':', '-')}-{step}.json").write_bytes(raw)
        if turn.get("delay_ms"):
            time.sleep(turn["delay_ms"] / 1000)
        identifier = f"chatcmpl-{scenario.replace(':', '-')}-{step}"
        if not body.get("stream"):
            return self.send_json(200, completion(identifier, model, turn, usage))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for event in chunks(identifier, model, turn, usage):
            self.wfile.write(f"data: {json.dumps(event)}\n\n".encode())
            self.wfile.flush()
            time.sleep(CHUNK_SECONDS)
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        self.close_connection = True


def main():
    print(f"llm-script: {len(load(SCENARIOS)['scenarios'])} scenarios from {SCENARIOS}, port {PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    sys.exit(main())
