from __future__ import annotations

import json
from typing import Any, Sequence

import httpx

from .errors import ProviderError
from .models import ProviderConfig


class OpenAICompatibleProvider:
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
    ) -> str:
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
            async with httpx.AsyncClient(
                timeout=self._timeout_seconds,
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
    normalized = base_url.strip().rstrip("/")
    if not normalized.endswith("/chat/completions"):
        normalized += "/chat/completions"
    return normalized
