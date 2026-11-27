"""归序代码生成工作室的模块内 AI 提案构建器。

本脚本负责代码生成提示词和输出结构归一化。它接收 Java 系统服务提供的当前文本
文件，向本机回环的归序 AI 服务发送一次通用补全请求，并返回声明式文件提案。
脚本不会写入文件，也不会直接读取仓库。
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
    """从标准输入读取一个 JSON 请求，并向标准输出写入一个 JSON 提案。"""
    try:
        raw = sys.stdin.read()
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ValueError("标准输入不是有效的 JSON 请求") from exc
        result = build_proposal(payload)
    except Exception as exc:  # 保持诊断稳定，且不得泄露密钥。
        print(str(exc) or "代码生成提案失败", file=sys.stderr)
        return 1
    json.dump(result, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    return 0


def build_proposal(payload: dict[str, Any]) -> dict[str, Any]:
    """校验请求结构，调用通用模型端点，并归一化输出。"""
    mode = _text(payload.get("mode"))
    if mode not in {"module", "motion"}:
        raise ValueError("mode 必须是 module 或 motion")
    prompt = _text(payload.get("prompt"))
    if not prompt:
        raise ValueError("必须提供 prompt")
    if len(prompt) > 8_000:
        raise ValueError("prompt 过大")

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
你是归序（Orderly）本地优先桌面应用的模块内生成器。
只返回一个 JSON 对象，不要在 JSON 外使用 Markdown 代码围栏或说明文字。
不得声称文件已经构建、应用或执行，只能提出完整文件。
路径必须是相对路径，使用正斜杠，且不得包含 '..'、盘符、开头的斜杠、NUL
或 module_system_ 目标。
输出总大小必须小于 900000 个 UTF-8 字节，文件步骤不得超过 48 个。
输出结构为：
{"moduleId":"module_example","summary":"简短说明","steps":[{"title":"...","path":"...","content":"..."}]}
summary 和每个步骤的 title 必须使用简体中文；路径、代码、标识符和协议字段保持原文。
修改现有目标时，只包含需要变更的文件。创建新模块时，必须包含可运行模块所需的
全部源码和资源文件。
""".strip()
    if mode == "module":
        return (
            common
            + """

模块模式要求：
- 只生成或修改一个普通业务模块。
- moduleId 必须匹配 ^module_[a-z0-9_]+$，并且与返回对象中的 moduleId 一致。
- 完整模块必须包含 pom.xml、
  src/main/resources/META-INF/services/com.orderly.kernel.OrderlyModule,
  src/main/resources/orderly-modules/<moduleId>.json,
  src/main/resources/orderly-ui/module.json,
  src/main/resources/orderly-ui/module.js,
  src/main/resources/orderly-ui/module.css，以及位于 src/main/java 下的
  Java OrderlyModule 实现。
- Maven 资源必须位于 src/main/resources 下。不要使用根目录级的 orderly-modules
  或 orderly-ui 目录，因为 Maven 不会打包这些目录。
- 模块契约必须公开每一个已实现 Action 和 Query。WRITE 和 DELETE
  Action 必须设置 confirmationRequired 为 true，不得声明 aiCallable。
- 模块代码只能使用公开的 OrderlyModule/ModuleRuntime SPI。不得导入其他业务模块，
  也不得读取任意文件系统路径。
- Java 类必须准确实现以下公有方法：
  moduleId(), query(String, Map<String,String>, ModuleRuntime),
  prepareSnapshot(ModuleInvocation, ModuleRuntime),
  execute(ModuleInvocation, ModuleRuntime)，以及
  rollback(ModuleInvocation, Map<String,Object>, Map<String,Object>, ModuleRuntime).
- src/main/resources/orderly-ui/module.js 是 ES module，必须包含顶层默认导出，例如
  `export default async function mount(root, context) { ... }`。
  不要生成 IIFE、Node export、registerView 调用或全局 OrderlyModules 注册表。
- src/main/resources/orderly-ui/module.json 必须使用以下精确的 version 1 结构：
  {"version":1,"view":{"id":"lowercase-view-id","icon":"spark","caption":"标题"},
  "card":{"title":"标题","description":"只读摘要","badge":"只读","icon":"spark",
  "queryId":"query.id"},"settings":[],"examples":[],"script":"module.js",
  "style":"module.css"}。不要使用 schemaVersion、moduleId、entry、styles 或 views。
- 模块契约中的每个 Query 都必须包含 id、title、description、parameters，以及
  JSON Schema 格式的 outputSchema。第一个 UI card 的 queryId 必须对应一个已实现 Query。
- pom.xml 必须使用仓库父工程、依赖 com.orderly:orderly-kernel、版本
  ${project.version}，并让 artifactId 等于 moduleId。
- UI CSS 必须使用语义变量，不得包含原始颜色字面量或固定动效时长。
- 如果请求需要修改框架行为或申请额外权限，不得写入模块，并在 summary 中说明该限制。
""".strip()
        )
    framework = motion_target == "framework"
    scope_rule = (
        "只能复用现有框架动效挂点，不得新增框架挂点。"
        if framework
        else "只能在所选模块 UI 内新增 data-motion-scope 挂点。"
    )
    target_rule = (
        f"框架目标主题为 {theme_id or '当前主题'}。"
        if framework
        else f"所选模块为 {target_module}。"
    )
    return (
        common
        + f"""

动效模式要求：
- {target_rule}
- {scope_rule}
- 框架动效只能修改主题的 motion.css 或 tokens.css 文件。
- 模块动效只能修改 src/main/resources/orderly-ui/module.css、
  src/main/resources/orderly-ui/module.js 以及
  src/main/resources/orderly-ui/module.json.
- 模块 CSS 必须使用语义变量，不得包含原始颜色、字体族或固定时长。
- 当 data-motion='none' 时，界面仍必须完全可用。
- 每个新增模块挂点都必须保留可见的降级位置和标签。
- 在 summary 中写明最终选择的挂点位置，但不得虚构运行时状态。
""".strip()
    )


def _complete(*, system_prompt: str, user_content: str) -> str:
    base_url = _text(os.environ.get("ORDERLY_AI_SERVICE_URL"))
    token = _text(os.environ.get("ORDERLY_AI_IPC_TOKEN"))
    if not base_url.startswith("http://127.0.0.1:"):
        raise ValueError("ORDERLY_AI_SERVICE_URL 必须指向本机回环 HTTP 地址")
    if not token:
        raise ValueError("尚未配置 ORDERLY_AI_IPC_TOKEN")
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
            response_body = response.read()
        try:
            payload = json.loads(response_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("模型服务返回了非 JSON 响应") from exc
    except error.HTTPError as exc:
        message = f"模型服务返回 HTTP {exc.code}"
        try:
            details = json.loads(exc.read().decode("utf-8"))
            safe_message = details.get("message")
            if isinstance(safe_message, str) and safe_message.strip():
                message = f"模型服务返回 HTTP {exc.code}：{safe_message.strip()[:300]}"
        except Exception:
            pass
        raise RuntimeError(message) from exc
    except error.URLError as exc:
        raise RuntimeError("模型服务不可用") from exc
    content = payload.get("content") if isinstance(payload, dict) else None
    if not isinstance(content, str) or not content.strip():
        raise ValueError("模型服务返回了空内容")
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
            raise ValueError("模型响应不是 JSON 对象") from exc
        try:
            value = json.loads(text[start : end + 1])
        except json.JSONDecodeError as nested:
            raise ValueError("模型响应不是 JSON 对象") from nested
    if not isinstance(value, dict):
        raise ValueError("模型响应必须是 JSON 对象")
    return value


def _normalize_proposal(
    proposal: dict[str, Any],
    *,
    mode: str,
    target_module: str,
) -> dict[str, Any]:
    summary = _text(proposal.get("summary"))
    if not summary:
        raise ValueError("提案必须包含 summary")
    module_id = _text(proposal.get("moduleId")) or target_module
    if mode == "module" and not module_id:
        raise ValueError("提案必须包含 moduleId")
    steps = proposal.get("steps")
    if not isinstance(steps, list) or not steps or len(steps) > MAX_OUTPUT_STEPS:
        raise ValueError("提案 steps 必须包含 1 至 48 项")
    normalized = []
    total = 0
    seen: set[str] = set()
    for index, raw_step in enumerate(steps):
        if not isinstance(raw_step, dict):
            raise ValueError(f"第 {index + 1} 步必须是对象")
        title = _text(raw_step.get("title")) or f"文件变更 {index + 1}"
        path = _safe_relative_path(_text(raw_step.get("path")))
        content = raw_step.get("content")
        if not isinstance(content, str):
            raise ValueError(f"第 {index + 1} 步的 content 必须是文本")
        if path in seen:
            raise ValueError(f"文件路径重复：{path}")
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
        raise ValueError("提案输出过大")
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
        raise ValueError("文件路径必须是相对路径")
    parts = normalized.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise ValueError("文件路径未规范化")
    if "\x00" in normalized:
        raise ValueError("文件路径包含 NUL 字符")
    return normalized


def _text(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


if __name__ == "__main__":
    raise SystemExit(main())
