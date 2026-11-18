from __future__ import annotations

from collections import deque

from conftest import AUTH_HEADERS, configure_settings, turn_body


QUESTION = (
    '{"kind":"QUESTION","message":"需要补充",'
    '"question":{"prompt":"请选择目标？","options":["A","B"]}}'
)


def test_plan_disabled_gets_one_repair_then_returns_question(make_client, provider):
    client = make_client()
    configure_settings(client)
    provider.responses = deque(
        [
            '{"kind":"PLAN","actions":[{"moduleId":"module_fs",'
            '"actionId":"fs.mkdir","arguments":{"path":"验收"}}]}',
            QUESTION,
        ]
    )

    response = client.post(
        "/v1/turns",
        headers=AUTH_HEADERS,
        json=turn_body(ai_plan_enabled=False),
    )

    assert response.status_code == 200
    assert response.json()["kind"] == "QUESTION"
    assert len(provider.calls) == 2
    repair_prompt = provider.calls[1].messages[-1]["content"]
    assert "ACTIONS、QUESTION" in repair_prompt
    assert "PLAN" not in repair_prompt


def test_plan_disabled_second_plan_fails_with_invalid_response(make_client, provider):
    client = make_client()
    configure_settings(client)
    plan = (
        '{"kind":"PLAN","actions":[{"moduleId":"module_fs",'
        '"actionId":"fs.mkdir","arguments":{"path":"验收"}}]}'
    )
    provider.responses = deque([plan, plan])

    response = client.post(
        "/v1/turns",
        headers=AUTH_HEADERS,
        json=turn_body(ai_plan_enabled=False),
    )

    assert response.status_code == 502
    assert response.json()["code"] == "AI_INVALID_RESPONSE"
    assert len(provider.calls) == 2


def test_query_is_available_by_default_and_limited_to_four(
    make_client,
    provider,
):
    client = make_client()
    configure_settings(client)
    four_queries = (
        '{"kind":"QUERY","message":"读取摘要","queries":['
        + ",".join(
            [
                '{"moduleId":"module_fs","queryId":"fs.summary","arguments":{}}'
            ]
            * 4
        )
        + "]}"
    )
    provider.responses.append(four_queries)

    response = client.post(
        "/v1/turns",
        headers=AUTH_HEADERS,
        json=turn_body(),
    )

    assert response.status_code == 200
    assert response.json()["kind"] == "QUERY"
    assert response.json()["conversationId"] == "conversation-1"
    assert set(response.json()) == {
        "kind",
        "conversationId",
        "message",
        "queries",
    }
    assert len(response.json()["queries"]) == 4
    assert set(response.json()["queries"][0]) == {
        "moduleId",
        "queryId",
        "arguments",
    }


def test_five_queries_trigger_one_repair_and_then_fail(make_client, provider):
    client = make_client()
    configure_settings(client)
    five_queries = (
        '{"kind":"QUERY","queries":['
        + ",".join(
            [
                '{"moduleId":"module_fs","queryId":"fs.summary","arguments":{}}'
            ]
            * 5
        )
        + "]}"
    )
    provider.responses = deque([five_queries, five_queries])

    response = client.post(
        "/v1/turns",
        headers=AUTH_HEADERS,
        json=turn_body(),
    )

    assert response.status_code == 502
    assert response.json()["code"] == "AI_INVALID_RESPONSE"
    assert len(provider.calls) == 2


def query_results_body(*, payload: dict | None = None) -> dict:
    result = {
        "moduleId": "module_fs",
        "queryId": "fs.summary",
        "status": "OK",
        "payload": payload or {"files": 3},
        "message": "",
    }
    return {
        **turn_body(
            goal="根据摘要继续",
            request_id="query-results-1",
        ),
        "queryResults": [result],
    }


def test_query_results_blocked_without_explicit_privacy_consent(
    make_client,
    provider,
):
    client = make_client()
    configure_settings(client, allow_query_results=False)

    response = client.post(
        "/v1/turns/query-results",
        headers=AUTH_HEADERS,
        json=query_results_body(),
    )

    assert response.status_code == 403
    assert response.json()["code"] == "AI_QUERY_RESULTS_NOT_ALLOWED"
    assert len(provider.calls) == 0


def test_query_results_are_sent_only_after_enabled_and_cannot_return_query(
    make_client,
    provider,
):
    client = make_client()
    configure_settings(client, allow_query_results=True)
    provider.responses = deque(
        [
            '{"kind":"QUERY","queries":[{"moduleId":"module_fs",'
            '"queryId":"fs.summary","arguments":{}}]}',
            QUESTION,
        ]
    )

    response = client.post(
        "/v1/turns/query-results",
        headers=AUTH_HEADERS,
        json=query_results_body(payload={"files": 3, "title": "摘要"}),
    )

    assert response.status_code == 200
    assert response.json()["kind"] == "QUESTION"
    result_message = provider.calls[0].messages[-1]["content"]
    assert '"files":3' in result_message
    assert "禁止再次 QUERY" in result_message
    assert "ACTIONS、PLAN、QUESTION" in provider.calls[1].messages[-1]["content"]


def test_query_result_validation_rejects_payload_for_failed_status(make_client):
    client = make_client()
    configure_settings(client, allow_query_results=True)
    body = query_results_body()
    body["queryResults"][0]["status"] = "FAILED"
    body["queryResults"][0]["payload"] = {"unexpected": True}

    response = client.post(
        "/v1/turns/query-results",
        headers=AUTH_HEADERS,
        json=body,
    )

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"


def test_single_query_result_over_16_kib_is_rejected(make_client, provider):
    client = make_client()
    configure_settings(client, allow_query_results=True)

    response = client.post(
        "/v1/turns/query-results",
        headers=AUTH_HEADERS,
        json=query_results_body(payload={"text": "x" * (17 * 1024)}),
    )

    assert response.status_code == 422
    assert response.json()["code"] == "AI_QUERY_RESULT_TOO_LARGE"
    assert len(provider.calls) == 0


def test_total_query_results_over_64_kib_is_rejected(
    make_client,
    provider,
    monkeypatch,
):
    monkeypatch.setattr(
        "orderly_ai.service.QUERY_RESULT_ITEM_LIMIT_BYTES",
        100 * 1024,
    )
    client = make_client()
    configure_settings(client, allow_query_results=True)
    body = query_results_body()
    body["queryResults"] = [
        {
            "moduleId": "module_fs",
            "queryId": "fs.summary",
            "status": "OK",
            "payload": {"text": "x" * (20 * 1024)},
            "message": "",
        }
        for _ in range(4)
    ]

    response = client.post(
        "/v1/turns/query-results",
        headers=AUTH_HEADERS,
        json=body,
    )

    assert response.status_code == 422
    assert response.json()["code"] == "AI_QUERY_RESULT_TOO_LARGE"
    assert "总计" in response.json()["message"]
    assert len(provider.calls) == 0


def test_query_results_array_is_limited_to_four(make_client):
    client = make_client()
    configure_settings(client, allow_query_results=True)
    body = query_results_body()
    body["queryResults"] = [
        {
            "moduleId": "module_fs",
            "queryId": "fs.summary",
            "status": "TRUNCATED",
            "message": "too large",
        }
    ] * 5

    response = client.post(
        "/v1/turns/query-results",
        headers=AUTH_HEADERS,
        json=body,
    )

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"
