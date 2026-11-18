from __future__ import annotations

import asyncio
import json
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol, Sequence

from .decisions import parse_decision, validate_decision_tools
from .errors import (
    DecisionError,
    ProviderError,
    ServiceError,
    invalid_response,
    provider_unavailable,
)
from .models import (
    ConnectionTestView,
    Decision,
    ProviderConfig,
    QueryResultsRequest,
    SettingsPutRequest,
    SettingsView,
    TurnRequest,
)
from .prompts import (
    build_query_results_message,
    build_repair_message,
    build_system_prompt,
)
from .settings import SettingsStore


MAX_HISTORY_MESSAGES = 12
QUERY_RESULT_ITEM_LIMIT_BYTES = 16 * 1024
QUERY_RESULTS_TOTAL_LIMIT_BYTES = 64 * 1024


class Provider(Protocol):
    async def complete(
        self,
        config: ProviderConfig,
        system_prompt: str,
        messages: Sequence[dict[str, str]],
    ) -> str: ...

    async def test(self, config: ProviderConfig) -> None: ...


@dataclass(slots=True)
class Conversation:
    messages: list[dict[str, str]]
    expires_at: float
    version: int


@dataclass(slots=True)
class Replay:
    fingerprint: str
    decision: Decision
    expires_at: float
    version: int


class AIService:
    def __init__(
        self,
        *,
        provider: Provider,
        settings: SettingsStore | None = None,
        ttl_seconds: float = 600.0,
        monotonic: Callable[[], float] = time.monotonic,
        local_now: Callable[[], datetime] | None = None,
    ) -> None:
        self.provider = provider
        self.settings = settings or SettingsStore()
        self.ttl_seconds = ttl_seconds
        self._monotonic = monotonic
        self._local_now = local_now or (lambda: datetime.now().astimezone())
        self._conversations: dict[tuple[int, str], Conversation] = {}
        self._replays: dict[tuple[str, int, str, str], Replay] = {}
        self._locks: dict[tuple[str, int, str, str], asyncio.Lock] = {}
        self._locks_guard = asyncio.Lock()

    def get_settings(self) -> SettingsView:
        return self.settings.view()

    def put_settings(self, request: SettingsPutRequest) -> SettingsView:
        return self.settings.configure(request)

    def delete_settings(self) -> SettingsView:
        return self.settings.clear()

    async def test_connection(self) -> ConnectionTestView:
        config = self.settings.require_configured()
        try:
            await self.provider.test(config)
        except ProviderError as exc:
            raise provider_unavailable() from exc
        return ConnectionTestView(ok=True, message="模型连接成功")

    async def turn(self, request: TurnRequest) -> Decision:
        config = self.settings.require_configured()
        replay_conversation_id = request.conversation_id or ""
        conversation_id = request.conversation_id or str(uuid.uuid4())
        return await self._run_completion(
            namespace="turn",
            request_id=request.client_request_id,
            conversation_id=conversation_id,
            replay_conversation_id=replay_conversation_id,
            goal=request.goal,
            ai_plan_enabled=request.ai_plan_enabled,
            modules=request.modules,
            config=config,
            allow_query=True,
            initial_user_message=request.goal,
            fingerprint_source={
                "goal": request.goal,
                "conversationId": replay_conversation_id,
            },
        )

    async def query_results(self, request: QueryResultsRequest) -> Decision:
        config = self.settings.require_configured()
        if not config.allow_send_query_results_to_model:
            raise ServiceError(
                403,
                "AI_QUERY_RESULTS_NOT_ALLOWED",
                "当前未允许将查询结果发送给模型。",
            )
        self._validate_query_result_sizes(request)
        serialized_results = [
            result.model_dump(by_alias=True, exclude_none=True)
            for result in request.query_results
        ]
        return await self._run_completion(
            namespace="query-results",
            request_id=request.client_request_id,
            conversation_id=request.conversation_id,
            replay_conversation_id=request.conversation_id,
            goal=request.goal,
            ai_plan_enabled=request.ai_plan_enabled,
            modules=request.modules,
            config=config,
            allow_query=False,
            initial_user_message=build_query_results_message(
                request.query_results
            ),
            fingerprint_source={
                "goal": request.goal,
                "conversationId": request.conversation_id,
                "queryResults": serialized_results,
            },
        )

    async def _run_completion(
        self,
        *,
        namespace: str,
        request_id: str,
        conversation_id: str,
        replay_conversation_id: str,
        goal: str,
        ai_plan_enabled: bool,
        modules: list,
        config: ProviderConfig,
        allow_query: bool,
        initial_user_message: str,
        fingerprint_source: dict,
    ) -> Decision:
        now_tick = self._monotonic()
        self._prune(now_tick, config.version)
        replay_key = (
            namespace,
            config.version,
            replay_conversation_id,
            request_id,
        )
        fingerprint = _stable_json(fingerprint_source)
        lock = await self._lock_for(replay_key)

        async with lock:
            now_tick = self._monotonic()
            self._prune(now_tick, config.version)
            replay = self._replays.get(replay_key)
            if replay is not None:
                if replay.fingerprint != fingerprint:
                    raise ServiceError(
                        409,
                        "AI_CLIENT_REQUEST_ID_CONFLICT",
                        "同一 clientRequestId 不能用于不同内容。",
                    )
                return replay.decision

            session_key = (config.version, conversation_id)
            session = self._conversations.get(session_key)
            history = [] if session is None else list(session.messages)
            messages = [
                *history,
                {"role": "user", "content": initial_user_message},
            ]
            allowed_kinds = self._allowed_kinds(
                ai_plan_enabled=ai_plan_enabled,
                allow_query=allow_query,
            )
            system_prompt = build_system_prompt(
                modules=modules,
                now=self._local_now(),
                ai_plan_enabled=ai_plan_enabled,
                allow_delete_direct=config.allow_delete_direct,
                allow_query=allow_query,
            )
            decision, raw = await self._complete_with_one_repair(
                config=config,
                system_prompt=system_prompt,
                messages=messages,
                allowed_kinds=allowed_kinds,
                modules=modules,
            )
            decision = decision.model_copy(
                update={"conversation_id": conversation_id}
            )

            if self.settings.snapshot().version != config.version:
                raise ServiceError(
                    409,
                    "AI_SETTINGS_CHANGED",
                    "AI 设置已变更，本次请求未执行；请重新提交。",
                )

            updated_history = [
                *messages,
                {"role": "assistant", "content": raw},
            ][-MAX_HISTORY_MESSAGES:]
            expires_at = self._monotonic() + self.ttl_seconds
            self._conversations[session_key] = Conversation(
                messages=updated_history,
                expires_at=expires_at,
                version=config.version,
            )
            self._replays[replay_key] = Replay(
                fingerprint=fingerprint,
                decision=decision,
                expires_at=expires_at,
                version=config.version,
            )
            return decision

    async def _complete_with_one_repair(
        self,
        *,
        config: ProviderConfig,
        system_prompt: str,
        messages: list[dict[str, str]],
        allowed_kinds: tuple[str, ...],
        modules: list,
    ) -> tuple[Decision, str]:
        try:
            raw = await self.provider.complete(
                config,
                system_prompt,
                messages,
            )
            try:
                return (
                    validate_decision_tools(
                        parse_decision(raw, allowed_kinds),
                        modules,
                    ),
                    raw,
                )
            except DecisionError as first_failure:
                repair_messages = [
                    *messages,
                    {"role": "assistant", "content": raw},
                    {
                        "role": "user",
                        "content": build_repair_message(
                            allowed_kinds=allowed_kinds,
                            reason=str(first_failure),
                        ),
                    },
                ]
                repaired = await self.provider.complete(
                    config,
                    system_prompt,
                    repair_messages,
                )
                try:
                    return (
                        validate_decision_tools(
                            parse_decision(repaired, allowed_kinds),
                            modules,
                        ),
                        repaired,
                    )
                except DecisionError as second_failure:
                    raise invalid_response(
                        "模型输出连续两次不符合受控决策契约"
                    ) from second_failure
        except ProviderError as exc:
            raise provider_unavailable() from exc

    @staticmethod
    def _allowed_kinds(
        *,
        ai_plan_enabled: bool,
        allow_query: bool,
    ) -> tuple[str, ...]:
        kinds = ["ACTIONS", "QUESTION"]
        if ai_plan_enabled:
            kinds.insert(1, "PLAN")
        if allow_query:
            kinds.append("QUERY")
        return tuple(kinds)

    def _validate_query_result_sizes(
        self,
        request: QueryResultsRequest,
    ) -> None:
        total = 0
        for result in request.query_results:
            size = len(
                _stable_json(
                    result.model_dump(by_alias=True, exclude_none=True)
                ).encode("utf-8")
            )
            if size > QUERY_RESULT_ITEM_LIMIT_BYTES:
                raise ServiceError(
                    422,
                    "AI_QUERY_RESULT_TOO_LARGE",
                    "单条查询结果不能超过 16 KiB。",
                )
            total += size
        if total > QUERY_RESULTS_TOTAL_LIMIT_BYTES:
            raise ServiceError(
                422,
                "AI_QUERY_RESULT_TOO_LARGE",
                "查询结果总计不能超过 64 KiB。",
            )

    async def _lock_for(
        self,
        key: tuple[str, int, str, str],
    ) -> asyncio.Lock:
        async with self._locks_guard:
            lock = self._locks.get(key)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[key] = lock
            return lock

    def _prune(self, now: float, version: int) -> None:
        self._conversations = {
            key: value
            for key, value in self._conversations.items()
            if value.version == version and now < value.expires_at
        }
        self._replays = {
            key: value
            for key, value in self._replays.items()
            if value.version == version and now < value.expires_at
        }


def _stable_json(value: object) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
