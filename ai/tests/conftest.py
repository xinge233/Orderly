from __future__ import annotations

from collections import deque
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

import pytest
from fastapi.testclient import TestClient

from orderly_ai.app import create_app
from orderly_ai.models import ProviderConfig


IPC_TOKEN = "test-ipc-token"
AUTH_HEADERS = {"Authorization": f"Bearer {IPC_TOKEN}"}


@dataclass
class ProviderCall:
    config: ProviderConfig
    system_prompt: str
    messages: list[dict[str, str]]
    timeout_seconds: float | None = None


@dataclass
class FakeProvider:
    responses: deque[str | Exception] = field(default_factory=deque)
    calls: list[ProviderCall] = field(default_factory=list)
    test_calls: int = 0

    async def complete(
        self,
        config: ProviderConfig,
        system_prompt: str,
        messages: Sequence[dict[str, str]],
        timeout_seconds: float | None = None,
    ) -> str:
        self.calls.append(
            ProviderCall(
                config=config,
                system_prompt=system_prompt,
                messages=[dict(message) for message in messages],
                timeout_seconds=timeout_seconds,
            )
        )
        if not self.responses:
            raise AssertionError("FakeProvider 没有可用响应")
        response = self.responses.popleft()
        if isinstance(response, Exception):
            raise response
        return response

    async def test(self, config: ProviderConfig) -> None:
        self.test_calls += 1
        if self.responses:
            response = self.responses.popleft()
            if isinstance(response, Exception):
                raise response


@pytest.fixture
def provider() -> FakeProvider:
    return FakeProvider()


@pytest.fixture
def make_client(provider: FakeProvider):
    clients: list[TestClient] = []

    def build(
        *,
        token: str | None = IPC_TOKEN,
        fake_provider: FakeProvider | None = None,
    ) -> TestClient:
        client = TestClient(
            create_app(
                ipc_token=token,
                provider=fake_provider or provider,
            )
        )
        clients.append(client)
        return client

    yield build
    for client in clients:
        client.close()


def configure_settings(
    client: TestClient,
    *,
    api_key: str = "temporary-secret-key",
    allow_delete_direct: bool = False,
) -> dict[str, Any]:
    response = client.put(
        "/v1/settings",
        headers=AUTH_HEADERS,
        json={
            "mode": "DEEPSEEK",
            "apiKey": api_key,
            "allowDeleteDirect": allow_delete_direct,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def module_cards() -> list[dict[str, Any]]:
    return [
        {
            "moduleId": "module_fs",
            "title": "资料管理",
            "description": "受控文件工具",
            "actions": [
                {
                    "actionId": "fs.mkdir",
                    "title": "创建目录",
                    "description": "在沙箱创建目录",
                    "risk": "WRITE",
                    "parameters": [
                        {
                            "name": "path",
                            "type": "string",
                            "required": True,
                            "description": "相对路径",
                        }
                    ],
                }
            ],
            "queries": [
                {
                    "queryId": "fs.summary",
                    "title": "文件摘要",
                    "description": "读取文件摘要",
                    "parameters": [],
                    "display": {
                        "prompt": "以文件数摘要展示文件概况，没有文件时显示空状态。",
                        "fallbackLayout": "keyValue",
                        "fields": [
                            {
                                "key": "files",
                                "label": "文件数",
                                "format": "number",
                            }
                        ],
                    },
                }
            ],
        }
    ]


def turn_body(
    *,
    goal: str = "创建一个验收目录",
    conversation_id: str | None = "conversation-1",
    request_id: str = "request-1",
    ai_plan_enabled: bool = True,
) -> dict[str, Any]:
    return {
        "goal": goal,
        "conversationId": conversation_id,
        "clientRequestId": request_id,
        "aiPlanEnabled": ai_plan_enabled,
        "modules": module_cards(),
    }
