"""模型文本到受控决策的解析、归一化与工具引用校验。

此模块是第一道结构闸门，但不是执行授权层。它只接受当前回合允许的决策
kind，并按工具卡核对模块、动作/查询标识及必填参数；真正的模块启用状态、
风险、确认、快照、审计与回滚仍由 Java 宿主担保。
"""

from __future__ import annotations

import json
import re
from typing import Any, Iterable

from pydantic import ValidationError

from .errors import DecisionError
from .models import (
    ActionCall,
    Decision,
    QueryCall,
    QueryResultItem,
    ToolModule,
)


FIELD_PATH = re.compile(r"^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$")


def parse_decision(raw: str, allowed_kinds: Iterable[str]) -> Decision:
    """解析模型输出并返回字段互斥且已归一化的决策。

    允许剥离常见的 Markdown 围栏以容纳模型格式噪声，但 JSON 本身仍严格
    解析：重复键、非法 kind、当前回合禁用的 kind 和跨 kind 字段都会失败。
    """
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
        "VIEW": {"kind", "message", "view"},
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
    query_results: list[QueryResultItem] | None = None,
) -> Decision:
    """确保模型没有发明工具、引用错误 kind 或遗漏必填参数。

    ``modules`` 由宿主限制为当前已启用的 AI 可调用工具。该校验只证明引用
    存在于本次工具卡，不证明用户已授权执行。
    """
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
    elif decision.kind == "VIEW":
        view = decision.view
        if view is None:
            raise DecisionError("VIEW 缺少 view")
        if not query_results:
            raise DecisionError("VIEW 只能用于 Query 结果续聊")
        if view.source_query > len(query_results):
            raise DecisionError("VIEW.sourceQuery 超出 Query 结果范围")
        source = query_results[view.source_query - 1]
        if source.status != "OK":
            raise DecisionError("VIEW 只能引用成功的 Query 结果")
        if view.row_path and not FIELD_PATH.fullmatch(view.row_path):
            raise DecisionError("VIEW.rowPath 只能使用点分隔字段名")
        row_node: Any = source.payload
        if view.row_path:
            row_node = resolve_path(row_node, view.row_path)
        if view.layout in {"list", "table"}:
            if not view.row_path:
                raise DecisionError("VIEW 的 list/table 布局需要 rowPath")
            if not isinstance(row_node, list):
                raise DecisionError("VIEW.rowPath 必须指向数组")
        sample = row_node[0] if isinstance(row_node, list) and row_node else row_node
        for field in view.fields:
            if not FIELD_PATH.fullmatch(field.key):
                raise DecisionError("VIEW.fields.key 只能使用点分隔字段名")
            if isinstance(sample, (dict, list)) and not path_exists(sample, field.key):
                raise DecisionError(f"VIEW.fields 引用了不存在的字段：{field.key}")
    return decision


def resolve_path(source: Any, path: str) -> Any:
    """按点分隔字段读取已有 payload；不执行表达式或数组索引。"""
    current = source
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current


def path_exists(source: Any, path: str) -> bool:
    """判断字段是否存在；字段值为 null 时仍视为已存在。"""
    current = source
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return False
        current = current[part]
    return True


def strip_code_fence(raw: str) -> str:
    """移除模型偶尔附加的代码围栏和对象外围文本。

    解析器仍要求截取后的内容是一个 JSON 对象，因此此步骤不放宽字段契约。
    """
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
    """拒绝重复 JSON 字段，防止“最后一次赋值”掩盖歧义或绕过校验。"""
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"重复字段：{key}")
        result[key] = value
    return result


def normalize_decision(decision: Decision) -> Decision:
    """按 kind 重建决策，清除空消息并强制字段互斥不变量。"""
    if decision.kind in {"ACTIONS", "PLAN"}:
        if not decision.actions:
            raise DecisionError(f"{decision.kind} 必须包含 actions")
        return Decision(
            kind=decision.kind,
            message=decision.message.strip(),
            actions=decision.actions,
        )
    if decision.kind == "QUERY":
        if (
            decision.actions
            or decision.question
            or decision.plan is not None
            or decision.view is not None
        ):
            raise DecisionError("QUERY 不得混入其他决策字段")
        queries = decision.queries or []
        if not 1 <= len(queries) <= 4:
            raise DecisionError("QUERY 必须包含 1 至 4 条查询")
        return Decision(
            kind="QUERY",
            message=decision.message.strip(),
            queries=queries,
        )

    if decision.kind == "VIEW":
        if decision.view is None:
            raise DecisionError("VIEW 缺少 view")
        if (
            decision.actions
            or decision.queries
            or decision.question
            or decision.plan is not None
        ):
            raise DecisionError("VIEW 不得混入其他决策字段")
        return Decision(
            kind="VIEW",
            message=decision.message.strip(),
            view=decision.view,
        )

    if not decision.question or not decision.question.prompt.strip():
        raise DecisionError("QUESTION 缺少具体问题")
    if (
        decision.actions
        or decision.queries
        or decision.plan is not None
        or decision.view is not None
    ):
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
    """按工具卡检查必填参数名；参数类型和业务约束仍由 Java 最终校验。"""
    for parameter in parameters:
        if parameter.required and parameter.name not in call.arguments:
            raise DecisionError(
                f"{type(call).__name__} 缺少必填参数 {parameter.name}"
            )
