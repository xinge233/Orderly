"""Module-local AI proposal builder for Orderly's code generation studio.

The script owns code-generation prompts and output-shape normalization. It receives
current text files from the Java system service, sends a single generic completion
request to the loopback Orderly AI service, and returns a declarative file proposal.
It never writes files or reads the repository directly.
"""

from __future__ import annotations

import json
import os
import sys
from typing import Any
from urllib import error, request


MAX_INPUT_FILES = 80
MAX_INPUT_BYTES = 700_000
MAX_OUTPUT_STEPS = 48
MAX_OUTPUT_BYTES = 900_000


def main() -> int:
    """Read one JSON request from stdin and write one JSON proposal to stdout."""
    try:
        raw = sys.stdin.read()
        payload = json.loads(raw)
        result = build_proposal(payload)
    except Exception as exc:  # Keep diagnostics stable and secret-free.
        print(str(exc) or "codegen proposal failed", file=sys.stderr)
        return 1
    json.dump(result, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    return 0


def build_proposal(payload: dict[str, Any]) -> dict[str, Any]:
    """Validate request shape, call the generic model endpoint, and normalize output."""
    mode = _text(payload.get("mode"))
    if mode not in {"module", "motion"}:
        raise ValueError("mode must be module or motion")
    prompt = _text(payload.get("prompt"))
    if not prompt:
        raise ValueError("prompt is required")
    if len(prompt) > 8_000:
        raise ValueError("prompt is too large")

    target_module = _text(payload.get("targetModuleId"))
    motion_target = _text(payload.get("motionTarget"))
    theme_id = _text(payload.get("themeId"))
    files = _bounded_files(payload.get("files"))
    catalog = payload.get("catalog")
    motion_scopes = payload.get("motionScopes")

    system_prompt = _system_prompt(
        mode=mode,
        target_module=target_module,
        motion_target=motion_target,
        theme_id=theme_id,
    )
    user_prompt = {
        "request": {
            "mode": mode,
            "prompt": prompt,
            "targetModuleId": target_module,
            "motionTarget": motion_target,
            "themeId": theme_id,
        },
        "existingFiles": files,
        "moduleCatalog": catalog if isinstance(catalog, list) else [],
        "motionScopes": motion_scopes if isinstance(motion_scopes, list) else [],
    }
    completion = _complete(
        system_prompt=system_prompt,
        user_content=json.dumps(
            user_prompt,
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    )
    proposal = _parse_json_object(completion)
    return _normalize_proposal(proposal, mode=mode, target_module=target_module)


def _system_prompt(
    *,
    mode: str,
    target_module: str,
    motion_target: str,
    theme_id: str,
) -> str:
    common = """
You are the module-local generator for Orderly, a local-first desktop application.
Return exactly one JSON object. Do not use Markdown fences or prose outside JSON.
Never claim that files were built, applied, or executed. Only propose complete files.
Paths must be relative, use forward slashes, and must never contain '..', drive
letters, leading slash, NUL, or module_system_ targets.
Keep total output below 900000 UTF-8 bytes and at most 48 file steps.
The output shape is:
{"moduleId":"module_example","summary":"short summary","steps":[{"title":"...","path":"...","content":"..."}]}
For an existing target, include only files that should change. For a new module,
include every source and resource file required for a runnable module.
""".strip()
    if mode == "module":
        return (
            common
            + """

Module mode requirements:
- Generate or modify one ordinary business module only.
- moduleId must match ^module_[a-z0-9_]+$ and must equal the returned moduleId.
- A complete module requires pom.xml,
  src/main/resources/META-INF/services/com.orderly.kernel.OrderlyModule,
  src/main/resources/orderly-modules/<moduleId>.json,
  src/main/resources/orderly-ui/module.json,
  src/main/resources/orderly-ui/module.js,
  src/main/resources/orderly-ui/module.css, and a Java OrderlyModule
  implementation under src/main/java.
- Maven resources must stay under src/main/resources. Never use root-level
  orderly-modules or orderly-ui directories, because Maven will not package them.
- The module contract must expose every implemented Action and Query. WRITE and DELETE
  actions must set confirmationRequired true. Do not declare aiCallable.
- Module code must use only the public OrderlyModule/ModuleRuntime SPI. Do not import
  another business module or read arbitrary filesystem paths.
- The Java class must implement exactly these public methods:
  moduleId(), query(String, Map<String,String>, ModuleRuntime),
  prepareSnapshot(ModuleInvocation, ModuleRuntime),
  execute(ModuleInvocation, ModuleRuntime), and
  rollback(ModuleInvocation, Map<String,Object>, Map<String,Object>, ModuleRuntime).
- src/main/resources/orderly-ui/module.js is an ES module and must contain a top-level
  default export such as `export default async function mount(root, context) { ... }`.
  Do not emit an IIFE, Node export, registerView call, or global OrderlyModules registry.
- src/main/resources/orderly-ui/module.json must use this exact version-1 shape:
  {"version":1,"view":{"id":"lowercase-view-id","icon":"spark","caption":"标题"},
  "card":{"title":"标题","description":"只读摘要","badge":"只读","icon":"spark",
  "queryId":"query.id"},"settings":[],"examples":[],"script":"module.js",
  "style":"module.css"}. Do not use schemaVersion, moduleId, entry, styles, or views.
- Every Query in the module contract must have id, title, description, parameters, and a
  JSON Schema outputSchema. The first UI card queryId must match an implemented Query.
- pom.xml must use the repository parent, dependency com.orderly:orderly-kernel with
  version ${project.version}, and artifactId equal to moduleId.
- UI CSS must use semantic variables and no raw color literals or fixed motion duration.
- If the request changes framework behavior or requires privileges, keep that out of the
  module and explain the limitation in summary.
""".strip()
        )
    framework = motion_target == "framework"
    scope_rule = (
        "Reuse existing framework motion hooks only. Do not add a new framework hook."
        if framework
        else "You may add data-motion-scope hooks only inside the selected module UI."
    )
    target_rule = (
        f"Framework target theme is {theme_id or 'the current theme'}."
        if framework
        else f"Selected module is {target_module}."
    )
    return (
        common
        + f"""

Motion mode requirements:
- {target_rule}
- {scope_rule}
- Framework motion may change only theme motion.css or tokens.css files.
- Module motion may change only src/main/resources/orderly-ui/module.css,
  src/main/resources/orderly-ui/module.js, and
  src/main/resources/orderly-ui/module.json.
- Module CSS must use semantic variables; no raw colors, font families, or fixed durations.
- Motion must remain fully usable when data-motion='none'.
- Every new module hook must keep its visible fallback position and label.
- Put the final selected hook positions in the summary, but do not invent runtime state.
""".strip()
    )


def _complete(*, system_prompt: str, user_content: str) -> str:
    base_url = _text(os.environ.get("ORDERLY_AI_SERVICE_URL"))
    token = _text(os.environ.get("ORDERLY_AI_IPC_TOKEN"))
    if not base_url.startswith("http://127.0.0.1:"):
        raise ValueError("ORDERLY_AI_SERVICE_URL must be a loopback HTTP endpoint")
    if not token:
        raise ValueError("ORDERLY_AI_IPC_TOKEN is not configured")
    endpoint = base_url.rstrip("/") + "/v1/model/complete"
    body = json.dumps(
        {
            "systemPrompt": system_prompt,
            "messages": [{"role": "user", "content": user_content}],
            "timeoutSeconds": 90,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    req = request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    try:
        with request.urlopen(req, timeout=95) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except error.HTTPError as exc:
        message = f"model service returned HTTP {exc.code}"
        try:
            details = json.loads(exc.read().decode("utf-8"))
            safe_message = details.get("message")
            if isinstance(safe_message, str) and safe_message.strip():
                message = safe_message.strip()[:300]
        except Exception:
            pass
        raise RuntimeError(message) from exc
    except error.URLError as exc:
        raise RuntimeError("model service is unavailable") from exc
    content = payload.get("content") if isinstance(payload, dict) else None
    if not isinstance(content, str) or not content.strip():
        raise ValueError("model service returned empty content")
    return content


def _parse_json_object(content: str) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end <= start:
            raise ValueError("model response is not a JSON object") from exc
        try:
            value = json.loads(text[start : end + 1])
        except json.JSONDecodeError as nested:
            raise ValueError("model response is not a JSON object") from nested
    if not isinstance(value, dict):
        raise ValueError("model response must be a JSON object")
    return value


def _normalize_proposal(
    proposal: dict[str, Any],
    *,
    mode: str,
    target_module: str,
) -> dict[str, Any]:
    summary = _text(proposal.get("summary"))
    if not summary:
        raise ValueError("proposal summary is required")
    module_id = _text(proposal.get("moduleId")) or target_module
    if mode == "module" and not module_id:
        raise ValueError("proposal moduleId is required")
    steps = proposal.get("steps")
    if not isinstance(steps, list) or not steps or len(steps) > MAX_OUTPUT_STEPS:
        raise ValueError("proposal steps must contain 1 to 48 items")
    normalized = []
    total = 0
    seen: set[str] = set()
    for index, raw_step in enumerate(steps):
        if not isinstance(raw_step, dict):
            raise ValueError(f"step {index + 1} must be an object")
        title = _text(raw_step.get("title")) or f"File change {index + 1}"
        path = _safe_relative_path(_text(raw_step.get("path")))
        content = raw_step.get("content")
        if not isinstance(content, str):
            raise ValueError(f"step {index + 1} content must be text")
        if path in seen:
            raise ValueError(f"duplicate file path: {path}")
        seen.add(path)
        total += len(content.encode("utf-8"))
        normalized.append(
            {
                "title": title[:200],
                "path": path,
                "content": content,
            }
        )
    if total > MAX_OUTPUT_BYTES:
        raise ValueError("proposal output is too large")
    return {
        "moduleId": module_id,
        "summary": summary[:2000],
        "steps": normalized,
    }


def _bounded_files(raw: Any) -> dict[str, str]:
    if not isinstance(raw, dict):
        return {}
    files: dict[str, str] = {}
    total = 0
    for path, content in list(raw.items())[:MAX_INPUT_FILES]:
        if not isinstance(path, str) or not isinstance(content, str):
            continue
        safe_path = _safe_relative_path(path)
        value = content[:120_000]
        total += len(value.encode("utf-8"))
        if total > MAX_INPUT_BYTES:
            break
        files[safe_path] = value
    return files


def _safe_relative_path(value: str) -> str:
    normalized = value.strip().replace("\\", "/")
    if not normalized or normalized.startswith("/") or ":" in normalized:
        raise ValueError("file path must be relative")
    parts = normalized.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise ValueError("file path is not normalized")
    if "\x00" in normalized:
        raise ValueError("file path contains NUL")
    return normalized


def _text(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


if __name__ == "__main__":
    raise SystemExit(main())
