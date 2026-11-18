from __future__ import annotations

import pytest

from conftest import (
    AUTH_HEADERS,
    IPC_TOKEN,
    configure_settings,
    turn_body,
)
from orderly_ai.errors import ProviderError
from orderly_ai.models import Decision


def test_health_is_public_and_other_endpoints_fail_closed(make_client):
    client = make_client()

    health = client.get("/v1/health")
    assert health.status_code == 200
    assert health.json() == {"status": "UP"}

    missing = client.get("/v1/settings")
    assert missing.status_code == 401
    assert missing.headers["www-authenticate"] == "Bearer"
    assert missing.json()["code"] == "UNAUTHORIZED"
    assert missing.json()["timestamp"].endswith("Z")

    wrong = client.get(
        "/v1/settings",
        headers={"Authorization": "Bearer wrong-token"},
    )
    assert wrong.status_code == 401

    authorized = client.get("/v1/settings", headers=AUTH_HEADERS)
    assert authorized.status_code == 200
    assert authorized.json()["allowSendQueryResultsToModel"] is False


@pytest.mark.parametrize(
    ("method", "path", "body"),
    [
        ("GET", "/v1/settings", None),
        (
            "PUT",
            "/v1/settings",
            {"mode": "DEEPSEEK", "apiKey": "temporary"},
        ),
        ("DELETE", "/v1/settings", None),
        ("POST", "/v1/settings/test", None),
        ("POST", "/v1/turns", turn_body()),
        (
            "POST",
            "/v1/turns/query-results",
            {
                **turn_body(request_id="query-request"),
                "queryResults": [
                    {
                        "moduleId": "module_fs",
                        "queryId": "fs.summary",
                        "status": "TRUNCATED",
                        "message": "too large",
                    }
                ],
            },
        ),
    ],
)
def test_every_protected_endpoint_requires_bearer_token(
    make_client,
    method,
    path,
    body,
):
    client = make_client()

    response = client.request(method, path, json=body)

    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHORIZED"


def test_missing_configured_ipc_token_returns_service_unavailable(make_client):
    client = make_client(token="")

    response = client.get("/v1/settings")

    assert response.status_code == 503
    assert response.json()["code"] == "AI_IPC_TOKEN_NOT_CONFIGURED"


def test_settings_fix_deepseek_endpoint_and_never_return_key(make_client):
    client = make_client()
    secret = "sk-must-never-leak"

    configured = configure_settings(client, api_key=secret)

    assert configured["mode"] == "DEEPSEEK"
    assert configured["baseUrl"] == "https://api.deepseek.com"
    assert configured["model"] == "deepseek-flash"
    assert configured["configured"] is True
    assert configured["allowSendQueryResultsToModel"] is False
    assert configured["settingsVersion"] == 1
    assert "apiKey" not in configured
    assert secret not in str(configured)

    fetched = client.get("/v1/settings", headers=AUTH_HEADERS)
    assert secret not in fetched.text

    retained = client.put(
        "/v1/settings",
        headers=AUTH_HEADERS,
        json={
            "mode": "DEEPSEEK",
            "allowDeleteDirect": True,
        },
    )
    assert retained.status_code == 200
    assert retained.json()["configured"] is True
    assert retained.json()["allowDeleteDirect"] is True
    assert retained.json()["settingsVersion"] == 2


def test_custom_url_requires_https_and_normalizes_path(make_client):
    client = make_client()
    configure_settings(client)

    rejected = client.put(
        "/v1/settings",
        headers=AUTH_HEADERS,
        json={
            "mode": "CUSTOM",
            "baseUrl": "http://example.com/v1",
            "model": "custom-model",
        },
    )
    assert rejected.status_code == 400
    assert rejected.json()["code"] == "AI_INVALID_BASE_URL"

    accepted = client.put(
        "/v1/settings",
        headers=AUTH_HEADERS,
        json={
            "mode": "CUSTOM",
            "baseUrl": "https://example.com/openai/v1/",
            "model": "custom-model",
        },
    )
    assert accepted.status_code == 200
    assert accepted.json()["baseUrl"] == "https://example.com/openai/v1"
    assert accepted.json()["model"] == "custom-model"


def test_custom_localhost_http_requires_explicit_development_flag(
    make_client,
    monkeypatch,
):
    monkeypatch.delenv("ORDERLY_AI_ALLOW_LOCAL_PROVIDER", raising=False)
    strict_client = make_client()
    rejected = strict_client.put(
        "/v1/settings",
        headers=AUTH_HEADERS,
        json={
            "mode": "CUSTOM",
            "apiKey": "temporary-secret-key",
            "baseUrl": "http://127.0.0.1:18080/v1",
            "model": "local-model",
        },
    )
    assert rejected.status_code == 400
    assert rejected.json()["code"] == "AI_INVALID_BASE_URL"

    monkeypatch.setenv("ORDERLY_AI_ALLOW_LOCAL_PROVIDER", "true")
    local_client = make_client()
    accepted = local_client.put(
        "/v1/settings",
        headers=AUTH_HEADERS,
        json={
            "mode": "CUSTOM",
            "apiKey": "temporary-secret-key",
            "baseUrl": "http://127.0.0.1:18080/v1",
            "model": "local-model",
        },
    )
    assert accepted.status_code == 200
    assert accepted.json()["allowLocalProvider"] is True


def test_delete_resets_settings_without_returning_key(make_client):
    client = make_client()
    configure_settings(
        client,
        api_key="delete-me",
        allow_query_results=True,
    )

    response = client.delete("/v1/settings", headers=AUTH_HEADERS)

    assert response.status_code == 200
    payload = response.json()
    assert payload["configured"] is False
    assert payload["allowDeleteDirect"] is False
    assert payload["allowSendQueryResultsToModel"] is False
    assert payload["settingsVersion"] == 2
    assert "delete-me" not in response.text


def test_request_and_provider_reprs_redact_api_key():
    from orderly_ai.models import ProviderConfig, SettingsPutRequest

    secret = "repr-secret"
    request = SettingsPutRequest.model_validate(
        {"mode": "DEEPSEEK", "apiKey": secret}
    )
    config = ProviderConfig(
        mode="DEEPSEEK",
        base_url="https://api.deepseek.com",
        model="deepseek-flash",
        api_key=secret,
        allow_delete_direct=False,
        allow_send_query_results_to_model=False,
        version=1,
    )

    assert secret not in repr(request)
    assert secret not in repr(config)
    assert "[redacted]" in repr(config)


def test_provider_and_validation_errors_have_stable_shape(make_client, provider):
    client = make_client()

    invalid = client.post(
        "/v1/turns",
        headers=AUTH_HEADERS,
        json={
            "goal": "",
            "conversationId": "conversation-1",
            "clientRequestId": "request-1",
            "aiPlanEnabled": True,
            "modules": [],
        },
    )
    assert invalid.status_code == 400
    assert set(invalid.json()) >= {"code", "message", "timestamp", "details"}
    assert invalid.json()["code"] == "INVALID_REQUEST"
    assert "input" not in str(invalid.json()).lower()

    configure_settings(client, api_key="provider-secret")
    provider.responses.append(ProviderError("upstream included provider-secret"))
    unavailable = client.post(
        "/v1/turns",
        headers=AUTH_HEADERS,
        json=turn_body(),
    )
    assert unavailable.status_code == 502
    assert unavailable.json() == {
        "code": "AI_PROVIDER_UNAVAILABLE",
        "message": "模型服务暂时不可用，请稍后重试。",
        "timestamp": unavailable.json()["timestamp"],
    }
    assert "provider-secret" not in unavailable.text


def test_settings_connection_test_uses_current_memory_key(make_client, provider):
    client = make_client()
    configure_settings(client, api_key="connection-secret")
    provider.responses.append("OK")

    response = client.post("/v1/settings/test", headers=AUTH_HEADERS)

    assert response.status_code == 200
    assert response.json() == {"ok": True, "message": "模型连接成功"}
    assert provider.test_calls == 1
    assert "connection-secret" not in response.text


def test_response_decision_has_no_internal_null_fields():
    decision = Decision(kind="QUESTION", message="", question={
        "prompt": "选择目标？",
        "options": [],
    })
    dumped = decision.model_dump(by_alias=True, exclude_none=True)
    assert dumped["kind"] == "QUESTION"
    assert "actions" not in dumped
    assert "queries" not in dumped
