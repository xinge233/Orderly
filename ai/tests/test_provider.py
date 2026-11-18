from __future__ import annotations

import asyncio
import json

import httpx

from orderly_ai.errors import ProviderError
from orderly_ai.models import ProviderConfig
from orderly_ai.provider import OpenAICompatibleProvider, chat_completions_endpoint


def provider_config(
    *,
    base_url: str = "https://example.com/v1",
    api_key: str = "provider-secret",
) -> ProviderConfig:
    return ProviderConfig(
        mode="CUSTOM",
        base_url=base_url,
        model="custom-model",
        api_key=api_key,
        allow_delete_direct=False,
        allow_send_query_results_to_model=False,
        version=1,
    )


def test_provider_sends_openai_compatible_request_and_parses_content():
    observed: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["url"] = str(request.url)
        observed["authorization"] = request.headers["authorization"]
        observed["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": '{"kind":"QUESTION"}'}}]},
        )

    provider = OpenAICompatibleProvider(
        transport=httpx.MockTransport(handler)
    )
    result = asyncio.run(
        provider.complete(
            provider_config(),
            "system rules",
            [{"role": "user", "content": "目标"}],
        )
    )

    assert result == '{"kind":"QUESTION"}'
    assert observed["url"] == "https://example.com/v1/chat/completions"
    assert observed["authorization"] == "Bearer provider-secret"
    payload = observed["payload"]
    assert payload["model"] == "custom-model"
    assert payload["messages"][0] == {
        "role": "system",
        "content": "system rules",
    }
    assert payload["temperature"] == 0.1
    assert payload["stream"] is False


def test_provider_accepts_structured_content_and_preserves_existing_endpoint():
    def handler(request: httpx.Request) -> httpx.Response:
        assert str(request.url) == "https://example.com/v1/chat/completions"
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": {"kind": "QUESTION"}}}]},
        )

    provider = OpenAICompatibleProvider(transport=httpx.MockTransport(handler))
    result = asyncio.run(
        provider.complete(
            provider_config(base_url="https://example.com/v1/chat/completions"),
            "system",
            [{"role": "user", "content": "目标"}],
        )
    )
    assert json.loads(result) == {"kind": "QUESTION"}


def test_provider_error_does_not_echo_key_or_response_body():
    secret = "secret-that-must-not-leak"

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            500,
            json={"message": f"upstream echoed {secret}"},
        )

    provider = OpenAICompatibleProvider(transport=httpx.MockTransport(handler))
    try:
        asyncio.run(
            provider.complete(
                provider_config(api_key=secret),
                "system",
                [{"role": "user", "content": "目标"}],
            )
        )
    except ProviderError as exc:
        assert str(exc) == "模型服务返回 HTTP 500。"
        assert secret not in str(exc)
    else:
        raise AssertionError("ProviderError expected")


def test_provider_network_error_is_sanitized():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection failed with provider-secret", request=request)

    provider = OpenAICompatibleProvider(transport=httpx.MockTransport(handler))
    try:
        asyncio.run(
            provider.complete(
                provider_config(),
                "system",
                [{"role": "user", "content": "目标"}],
            )
        )
    except ProviderError as exc:
        assert str(exc) == "无法连接模型服务。"
        assert "provider-secret" not in str(exc)
    else:
        raise AssertionError("ProviderError expected")


def test_endpoint_builder_appends_chat_completions_once():
    assert (
        chat_completions_endpoint("https://api.deepseek.com/")
        == "https://api.deepseek.com/chat/completions"
    )
    assert (
        chat_completions_endpoint("https://host.example/v1/chat/completions")
        == "https://host.example/v1/chat/completions"
    )
