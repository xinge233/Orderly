"""短期 AI 回合编排、重放保护、会话管理与会话内工具校验。

服务只处理受控决策，不执行 Action 或 Query。会话、重放结果、设置和 API Key
均保存在当前 Python 进程内存中；Java 宿主负责最终契约校验、授权、快照、
执行、审计和回滚。所有请求通过 ``clientRequestId`` 建立幂等边界，设置代次
变化后旧状态不得复用。
"""

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


# 只保留最近十二个 user/assistant 消息，限制上下文增长和敏感内容驻留时间。
MAX_HISTORY_MESSAGES = 12
# 大小按稳定紧凑 JSON 的 UTF-8 字节计算，匹配发给模型前的实际序列化形态。
QUERY_RESULT_ITEM_LIMIT_BYTES = 16 * 1024
QUERY_RESULTS_TOTAL_LIMIT_BYTES = 64 * 1024


class Provider(Protocol):
    """模型调用端口；实现不得获得业务执行或文件访问能力。"""

    async def complete(
        self,
        config: ProviderConfig,
        system_prompt: str,
        messages: Sequence[dict[str, str]],
    ) -> str: ...

    async def test(self, config: ProviderConfig) -> None: ...


@dataclass(slots=True)
class Conversation:
    """同一设置代次下的一段短时会话历史。"""

    messages: list[dict[str, str]]
    expires_at: float
    version: int


@dataclass(slots=True)
class Replay:
    """已成功完成的请求结果，用于 HTTP 重试时返回首次决策。"""

    fingerprint: str
    decision: Decision
    expires_at: float
    version: int


class AIService:
    """协调 Provider 调用并维护进程内短期状态。

    核心不变量：同一 ``clientRequestId`` 不能对应不同语义内容；模型结构
    违规最多修复一次；设置变更后旧会话和重放结果不能被复用；Query 结果
    未经显式隐私同意不得发送给外部模型。
    """

    def __init__(
        self,
        *,
        provider: Provider,
        settings: SettingsStore | None = None,
        ttl_seconds: float = 600.0,
        monotonic: Callable[[], float] = time.monotonic,
        local_now: Callable[[], datetime] | None = None,
    ) -> None:
        """创建以单调时钟管理 TTL、以设置代次隔离状态的进程内服务。"""
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
        """返回不含 Key 的当前设置。"""
        return self.settings.view()

    def put_settings(self, request: SettingsPutRequest) -> SettingsView:
        """更新内存设置；配置版本由设置存储负责递增。"""
        return self.settings.configure(request)

    def delete_settings(self) -> SettingsView:
        """清除 Key 和安全开关。"""
        return self.settings.clear()

    async def test_connection(self) -> ConnectionTestView:
        """验证当前 Provider 可调用，并把失败映射为安全的上游错误。"""
        config = self.settings.require_configured()
        try:
            await self.provider.test(config)
        except ProviderError as exc:
            raise provider_unavailable() from exc
        return ConnectionTestView(ok=True, message="模型连接成功")

    async def turn(self, request: TurnRequest) -> Decision:
        """处理首轮目标并允许模型在有界范围内提出 Query。"""
        config = self.settings.require_configured()
        # 没有 conversationId 时仍以空串构造重放键，使相同请求重试返回同一
        # 首次决策；随机生成的 ID 只用于响应和会话写入。
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
            allow_view=False,
            query_results=None,
            initial_user_message=request.goal,
            fingerprint_source={
                "goal": request.goal,
                "conversationId": replay_conversation_id,
            },
        )

    async def query_results(self, request: QueryResultsRequest) -> Decision:
        """提交只读 Query 结果，并禁止续聊再次发起 Query。

        隐私开关先于任何序列化或 Provider 调用检查，确保未获同意时结果不会
        离开本机进程。通过开关后再检查单条与总计 UTF-8 大小上限。
        """
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
            allow_view=True,
            query_results=request.query_results,
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
        allow_view: bool,
        query_results: list | None,
        initial_user_message: str,
        fingerprint_source: dict,
    ) -> Decision:
        """在单一请求锁内完成去重、会话读取、模型调用和状态提交。

        重放键包含命名空间、设置代次、调用方会话 ID 和请求 ID。相同键若
        指纹不同返回 409；相同指纹直接返回首次决策。成功结果、会话历史和
        重放记录使用同一 TTL，并在 Provider 返回后再次核对设置代次，确保
        安全开关在调用途中的变化不会被旧结果掩盖。
        """
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
            # 等锁期间可能有其他请求完成或 TTL 到期，因此进入临界区后重新清理。
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
                allow_view=allow_view,
            )
            system_prompt = build_system_prompt(
                modules=modules,
                now=self._local_now(),
                ai_plan_enabled=ai_plan_enabled,
                allow_delete_direct=config.allow_delete_direct,
                allow_query=allow_query,
                allow_view=allow_view,
            )
            decision, raw = await self._complete_with_one_repair(
                config=config,
                system_prompt=system_prompt,
                messages=messages,
                allowed_kinds=allowed_kinds,
                modules=modules,
                query_results=query_results,
            )
            decision = decision.model_copy(
                update={"conversation_id": conversation_id}
            )

            # 模型调用期间设置可能被更新；此时拒绝提交，防止旧 Key/旧开关
            # 产生的决策在设置代次变化后仍被复用。
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
            # 会话和重放共享过期时间，避免一条消息对应另一条已失效状态。
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
        query_results: list | None,
    ) -> tuple[Decision, str]:
        """调用 Provider，并在首次契约失败时只进行一次定向修复。

        修复请求把原始响应和失败原因追加回模型上下文；第二次仍不符合契约
        时返回 ``AI_INVALID_RESPONSE``。Provider 网络或 HTTP 故障直接映射为
        ``AI_PROVIDER_UNAVAILABLE``，不会伪装成模型格式错误。
        """
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
                        query_results,
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
                            query_results,
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
        allow_view: bool,
    ) -> tuple[str, ...]:
        """根据计划功能和回合阶段计算允许的决策集合。"""
        kinds = ["ACTIONS", "QUESTION"]
        if ai_plan_enabled:
            kinds.insert(1, "PLAN")
        if allow_query:
            kinds.append("QUERY")
        if allow_view:
            kinds.append("VIEW")
        return tuple(kinds)

    def _validate_query_result_sizes(
        self,
        request: QueryResultsRequest,
    ) -> None:
        """按稳定紧凑 JSON 的 UTF-8 大小执行单条和总计限制。"""
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
        """为同一重放键复用请求锁，防止并发重试重复调用模型。"""
        async with self._locks_guard:
            lock = self._locks.get(key)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[key] = lock
            return lock

    def _prune(self, now: float, version: int) -> None:
        """删除过期或其他设置代次的会话与重放结果。"""
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
    """生成用于指纹和大小计算的确定性紧凑 JSON。"""
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
