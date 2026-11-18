from __future__ import annotations

import asyncio
from datetime import datetime, timezone

from conftest import FakeProvider
from orderly_ai.models import SettingsPutRequest, TurnRequest
from orderly_ai.service import AIService
from orderly_ai.settings import SettingsStore


QUESTION = (
    '{"kind":"QUESTION","message":"请选择",'
    '"question":{"prompt":"选择哪个目录？","options":["A","B"]}}'
)


class ManualClock:
    def __init__(self) -> None:
        self.value = 1000.0

    def __call__(self) -> float:
        return self.value

    def advance(self, seconds: float) -> None:
        self.value += seconds


def turn_request(
    *,
    request_id: str = "request-1",
    goal: str = "整理目录",
    conversation_id: str | None = "conversation-1",
) -> TurnRequest:
    return TurnRequest.model_validate(
        {
            "goal": goal,
            "conversationId": conversation_id,
            "clientRequestId": request_id,
            "aiPlanEnabled": True,
            "modules": [],
        }
    )


def test_replay_returns_first_decision_then_expires_by_ttl():
    async def scenario() -> None:
        clock = ManualClock()
        provider = FakeProvider(
            responses=__import__("collections").deque([QUESTION, QUESTION])
        )
        settings = SettingsStore()
        settings.configure(
            SettingsPutRequest(
                mode="DEEPSEEK",
                apiKey="secret",
            )
        )
        service = AIService(
            provider=provider,
            settings=settings,
            ttl_seconds=600,
            monotonic=clock,
            local_now=lambda: datetime(2026, 11, 18, tzinfo=timezone.utc),
        )

        first = await service.turn(turn_request())
        replay = await service.turn(turn_request())
        assert first == replay
        assert len(provider.calls) == 1

        clock.advance(601)
        after_expiry = await service.turn(turn_request())
        assert after_expiry.kind == "QUESTION"
        assert len(provider.calls) == 2

    asyncio.run(scenario())


def test_same_request_id_with_different_goal_conflicts_without_new_model_call():
    async def scenario() -> None:
        clock = ManualClock()
        provider = FakeProvider(
            responses=__import__("collections").deque([QUESTION])
        )
        settings = SettingsStore()
        settings.configure(
            SettingsPutRequest(mode="DEEPSEEK", apiKey="secret")
        )
        service = AIService(
            provider=provider,
            settings=settings,
            monotonic=clock,
        )
        await service.turn(turn_request(goal="第一个目标"))

        try:
            await service.turn(
                turn_request(request_id="request-1", goal="不同目标")
            )
        except Exception as exc:
            assert getattr(exc, "code", "") == "AI_CLIENT_REQUEST_ID_CONFLICT"
        else:
            raise AssertionError("冲突异常预期")
        assert len(provider.calls) == 1

    asyncio.run(scenario())


def test_settings_version_invalidates_replay_and_conversation():
    async def scenario() -> None:
        clock = ManualClock()
        provider = FakeProvider(
            responses=__import__("collections").deque([QUESTION, QUESTION])
        )
        settings = SettingsStore()
        settings.configure(
            SettingsPutRequest(mode="DEEPSEEK", apiKey="secret")
        )
        service = AIService(
            provider=provider,
            settings=settings,
            monotonic=clock,
        )
        await service.turn(turn_request())
        settings.configure(
            SettingsPutRequest(mode="DEEPSEEK", allowDeleteDirect=True)
        )

        await service.turn(turn_request())

        assert len(provider.calls) == 2
        assert provider.calls[1].config.version == 2
        assert len(provider.calls[1].messages) == 1

    asyncio.run(scenario())


def test_missing_conversation_id_is_generated_and_replayed_stably():
    async def scenario() -> None:
        clock = ManualClock()
        provider = FakeProvider(
            responses=__import__("collections").deque([QUESTION, QUESTION])
        )
        settings = SettingsStore()
        settings.configure(
            SettingsPutRequest(mode="DEEPSEEK", apiKey="secret")
        )
        service = AIService(
            provider=provider,
            settings=settings,
            monotonic=clock,
        )

        first = await service.turn(turn_request(conversation_id=None))
        replay = await service.turn(turn_request(conversation_id=None))

        assert first.conversation_id
        assert replay.conversation_id == first.conversation_id
        assert len(provider.calls) == 1

    asyncio.run(scenario())
