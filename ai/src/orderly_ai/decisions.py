from __future__ import annotations

import json
from typing import Any, Iterable

from pydantic import ValidationError

from .errors import DecisionError
from .models import (
    ActionCall,
    Decision,
    QueryCall,
    ToolModule,
)


def parse_decision(raw: str, allowed_kinds: Iterable[str]) -> Decision:
    allowed = tuple(allowed_kinds)
    stripped = strip_code_fence(raw)
    try:
        value = json.loads(stripped, object_pairs_hook=reject_duplicate_keys)
    except (json.JSONDecodeError, ValueError) as exc:
        raise DecisionError("模型输出不是合法 JSON") from exc
    if not isinstance(value, dict):
        raise DecisionError("模型输出必须是 JSON 对象")

    kind = str(value.get("kind", "")).strip().upper()
    if kind not in allowed:
        raise DecisionError("模型输出 kind 非法或当前不可用")
    value["kind"] = kind
    allowed_fields = {
        "ACTIONS": {"kind", "message", "actions"},
        "PLAN": {"kind", "message", "actions"},
        "QUESTION": {"kind", "message", "question"},
        "QUERY": {"kind", "message", "queries"},
    }[kind]
    unexpected = sorted(set(value) - allowed_fields)
    if unexpected:
        raise DecisionError(
            f"模型输出包含当前 kind 不允许的字段：{', '.join(unexpected)}"
        )

    try:
        decision = Decision.model_validate(value)
    except ValidationError as exc:
        raise DecisionError("模型输出字段不符合决策契约") from exc
    return normalize_decision(decision)


def validate_decision_tools(
    decision: Decision,
    modules: Iterable[ToolModule],
) -> Decision:
    module_map = {module.module_id: module for module in modules}
    if decision.kind in {"ACTIONS", "PLAN"}:
        for action in decision.actions or []:
            module = module_map.get(action.module_id)
            if module is None:
                raise DecisionError("Action 引用了未提供的模块")
            card = next(
                (item for item in module.actions if item.action_id == action.action_id),
                None,
            )
            if card is None:
                raise DecisionError("Action 引用了未提供的动作")
            validate_required_arguments(action, card.parameters)
    elif decision.kind == "QUERY":
        queries = decision.queries or []
        if not 1 <= len(queries) <= 4:
            raise DecisionError("QUERY 必须包含 1 至 4 条查询")
        for query in queries:
            module = module_map.get(query.module_id)
            if module is None:
                raise DecisionError("Query 引用了未提供的模块")
            card = next(
                (item for item in module.queries if item.query_id == query.query_id),
                None,
            )
            if card is None:
                raise DecisionError("Query 引用了未提供的查询")
            validate_required_arguments(query, card.parameters)
    return decision


def strip_code_fence(raw: str) -> str:
    value = (raw or "").strip()
    if value.startswith("```"):
        first_newline = value.find("\n")
        if first_newline >= 0:
            value = value[first_newline + 1 :]
        last_fence = value.rfind("```")
        if last_fence >= 0:
            value = value[:last_fence]
    start = value.find("{")
    end = value.rfind("}")
    if start >= 0 and end > start:
        value = value[start : end + 1]
    return value.strip()


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"重复字段：{key}")
        result[key] = value
    return result


def normalize_decision(decision: Decision) -> Decision:
    if decision.kind in {"ACTIONS", "PLAN"}:
        if not decision.actions:
            raise DecisionError(f"{decision.kind} 必须包含 actions")
        return Decision(
            kind=decision.kind,
            message=decision.message.strip(),
            actions=decision.actions,
        )
    if decision.kind == "QUERY":
        if decision.actions or decision.question or decision.plan is not None:
            raise DecisionError("QUERY 不得混入其他决策字段")
        queries = decision.queries or []
        if not 1 <= len(queries) <= 4:
            raise DecisionError("QUERY 必须包含 1 至 4 条查询")
        return Decision(
            kind="QUERY",
            message=decision.message.strip(),
            queries=queries,
        )

    if not decision.question or not decision.question.prompt.strip():
        raise DecisionError("QUESTION 缺少具体问题")
    if decision.actions or decision.queries or decision.plan is not None:
        raise DecisionError("QUESTION 不得混入其他决策字段")
    question = decision.question.model_copy(
        update={
            "prompt": decision.question.prompt.strip(),
            "options": [
                option.strip()
                for option in decision.question.options
                if option.strip()
            ],
        }
    )
    return Decision(
        kind="QUESTION",
        message=decision.message.strip(),
        question=question,
    )


def validate_required_arguments(
    call: ActionCall | QueryCall,
    parameters: Iterable[Any],
) -> None:
    for parameter in parameters:
        if parameter.required and parameter.name not in call.arguments:
            raise DecisionError(
                f"{type(call).__name__} 缺少必填参数 {parameter.name}"
            )
