"""OpenAI Chat Completions 兼容 Provider。

Provider 只发送系统规则、短时会话和工具卡生成的目标上下文，并把响应文本
交由决策层校验。它不执行 Action、不访问文件，也不把请求头或上游响应正文
写入日志。每次调用都设置硬超时且禁止重定向，网络重试不属于本模块。
"""

from __future__ import annotations

import json
from typing import Any, Sequence

import httpx

from .errors import ProviderError
from .models import ProviderConfig


class OpenAICompatibleProvider:
    """通过 HTTPS 调用 DeepSeek 或用户配置的兼容端点。

    模型响应在这里仍是不受信任的原始文本；结构化解析、工具引用校验和
    唯一一次“格式修复”由 ``AIService`` 负责。
    """

    def __init__(
        self,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout_seconds: float = 45.0,
    ) -> None:
        self._transport = transport
        self._timeout_seconds = timeout_seconds

    async def complete(
        self,
        config: ProviderConfig,
        system_prompt: str,
        messages: Sequence[dict[str, str]],
        timeout_seconds: float | None = None,
    ) -> str:
        """完成一次非流式模型调用并提取文本内容。

        超时和所有网络/HTTP/响应结构异常都会映射为不带上游正文的
        ``ProviderError``。这里不重试请求，避免重复计费或与上层格式修复
        的重试语义混淆。
        """
        if not config.configured or config.api_key is None:
            raise ProviderError("AI 尚未配置 API Key。")

        payload_messages = [
            {"role": "system", "content": system_prompt},
            *messages,
        ]
        payload = {
            "model": config.model,
            "messages": payload_messages,
            "temperature": 0.1,
            "stream": False,
        }
        headers = {
            "Authorization": f"Bearer {config.api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

        try:
            # 禁止跟随重定向，防止 Bearer Key 被转发到配置之外的地址。
            async with httpx.AsyncClient(
                timeout=timeout_seconds or self._timeout_seconds,
                follow_redirects=False,
                transport=self._transport,
            ) as client:
                response = await client.post(
                    chat_completions_endpoint(config.base_url),
                    headers=headers,
                    json=payload,
                )
        except (httpx.HTTPError, ValueError) as exc:
            raise ProviderError("无法连接模型服务。") from exc

        if not 200 <= response.status_code < 300:
            # 只保留状态码；上游正文可能回显请求内容或秘密。
            raise ProviderError(f"模型服务返回 HTTP {response.status_code}。")

        try:
            root: Any = response.json()
            content = root["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise ProviderError("模型服务返回无法解析的响应。") from exc

        if isinstance(content, str) and content.strip():
            return content
        if isinstance(content, (dict, list)):
            return json.dumps(content, ensure_ascii=False)
        raise ProviderError("模型服务返回空内容。")

    async def test(self, config: ProviderConfig) -> None:
        await self.complete(
            config,
            "这是连接测试。只返回 OK。",
            [{"role": "user", "content": "连接测试"}],
        )


def chat_completions_endpoint(base_url: str) -> str:
    """规范化 Base URL，并确保只追加一次 chat completions 路径。"""
    normalized = base_url.strip().rstrip("/")
    if not normalized.endswith("/chat/completions"):
        normalized += "/chat/completions"
    return normalized
