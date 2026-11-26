"""Java IPC 与模型决策共用的 Pydantic 数据模型。

模型负责结构、长度和字段互斥的基础校验，最终授权、风险确认与业务参数校验
仍由 Java 宿主完成。工具卡在此只作为数据处理；模型文本不得被当作指令。
API Key 只存在于 ``ProviderConfig`` 的进程内存实例中，公开视图和 repr 均
不得回显它。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


RISK_LEVEL = Literal["READ_ONLY", "WRITE", "DELETE"]
DECISION_KIND = Literal["ACTIONS", "PLAN", "QUESTION", "QUERY", "VIEW"]
QUERY_RESULT_STATUS = Literal["OK", "FAILED", "TRUNCATED"]
VIEW_LAYOUT = Literal["keyValue", "list", "table", "text", "timeline"]
VIEW_FORMAT = Literal["text", "status", "date", "time", "datetime", "number", "boolean"]
VIEW_ROLE = Literal["time", "primary", "secondary", "status"]
VIEW_DENSITY = Literal["compact", "comfortable"]
VIEW_GROUP_BY = Literal["none", "date"]


def to_camel(value: str) -> str:
    """把 Python 蛇形字段名转换为跨语言契约使用的 camelCase。"""
    first, *remaining = value.split("_")
    return first + "".join(part.capitalize() for part in remaining)


class CamelModel(BaseModel):
    """统一使用 camelCase 别名，同时允许 Python 侧按字段名构造模型。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        use_enum_values=True,
    )


class SettingsPutRequest(CamelModel):
    """AI 设置的局部更新请求。

    省略 ``api_key`` 表示保留进程内存中的旧 Key；响应永远只返回
    ``configured`` 状态，不返回 Key 本身。
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        use_enum_values=True,
        extra="forbid",
    )

    mode: Literal["DEEPSEEK", "CUSTOM"] | None = None
    api_key: str | None = Field(default=None, repr=False)
    base_url: str | None = None
    model: str | None = None
    allow_delete_direct: bool | None = None

    @field_validator("api_key")
    @classmethod
    def normalize_api_key(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None


class SettingsView(CamelModel):
    """可安全返回给前端的设置投影，刻意不包含 API Key 字段。"""

    mode: Literal["DEEPSEEK", "CUSTOM"]
    base_url: str
    model: str
    configured: bool
    allow_delete_direct: bool
    allow_local_provider: bool = False
    settings_version: int


class ConnectionTestView(CamelModel):
    """最小模型连通性测试的公开结果。"""

    ok: bool
    message: str


class ModelMessage(CamelModel):
    """通用模型补全请求中的单条消息。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        use_enum_values=True,
        extra="forbid",
    )

    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=200_000)


class ModelCompletionRequest(CamelModel):
    """受控通用补全请求。

    该端点只复用当前进程内存中的模型配置，不执行 Action、不访问文件，也不
    维护业务会话。系统提示词与模块提示词由调用方提供，根 AI 服务不保存它们。
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        use_enum_values=True,
        extra="forbid",
    )

    system_prompt: str = Field(min_length=1, max_length=100_000)
    messages: list[ModelMessage] = Field(min_length=1, max_length=8)
    timeout_seconds: float = Field(default=45.0, ge=5.0, le=90.0)


class ModelCompletionView(CamelModel):
    """通用补全结果；只返回模型文本，不包含凭据或上游响应元数据。"""

    content: str


class ParameterCard(CamelModel):
    """宿主提供的参数说明；额外元数据被忽略以减少模型适配耦合。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    name: str = Field(min_length=1)
    type: str = Field(min_length=1)
    required: bool = False
    description: str = ""


class ActionCard(CamelModel):
    """允许模型引用的 Action 卡。

    ``risk`` 仅用于决策提示和展示，不是授权真相源；Java 宿主必须重新确定
    风险、确认、快照与回滚要求。
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    action_id: str = Field(min_length=1)
    title: str = ""
    description: str = ""
    risk: RISK_LEVEL
    parameters: list[ParameterCard] = Field(default_factory=list)


class QueryDisplayField(CamelModel):
    """模块声明的 Query 展示字段。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    key: str = Field(min_length=1, max_length=160)
    label: str = Field(min_length=1, max_length=80)
    format: VIEW_FORMAT = "text"
    role: VIEW_ROLE | None = None
    value_labels: dict[str, str] = Field(default_factory=dict, max_length=64)


class QueryDisplay(CamelModel):
    """模块提供的展示建议；内容始终是不可信数据。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    prompt: str = Field(min_length=1, max_length=1000)
    fallback_layout: VIEW_LAYOUT
    row_path: str | None = Field(default=None, max_length=200)
    fields: list[QueryDisplayField] = Field(default_factory=list, max_length=12)
    empty_text: str | None = Field(default=None, max_length=200)
    density: VIEW_DENSITY = "comfortable"
    group_by: VIEW_GROUP_BY = "none"
    hide_repeated_fields: bool = False
    detail_fields: list[QueryDisplayField] = Field(default_factory=list, max_length=12)
    emphasis_fields: list[str] = Field(default_factory=list, max_length=2)


class QueryCard(CamelModel):
    """允许模型提出只读查询的 Query 卡。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    query_id: str = Field(min_length=1)
    title: str = ""
    description: str = ""
    parameters: list[ParameterCard] = Field(default_factory=list)
    output_schema: dict[str, Any] | None = None
    display: QueryDisplay | None = None


class ToolModule(CamelModel):
    """一个已启用模块可直接暴露给模型的 Action/Query 工具清单。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    module_id: str = Field(min_length=1)
    title: str = ""
    description: str = ""
    actions: list[ActionCard] = Field(default_factory=list)
    queries: list[QueryCard] = Field(default_factory=list)


class TurnRequest(CamelModel):
    """Java 发起的单回合目标请求。

    ``client_request_id`` 是重放去重键的一部分；即使调用方重试 HTTP 请求，
    同一回合也不能被解释为新的写操作意图。
    """

    goal: str = Field(min_length=1, max_length=4000)
    conversation_id: str | None = Field(default=None, max_length=200)
    client_request_id: str = Field(min_length=1, max_length=200)
    ai_plan_enabled: bool
    modules: list[ToolModule] = Field(default_factory=list)

    @field_validator("goal", "client_request_id")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("不能为空")
        return normalized

    @field_validator("conversation_id")
    @classmethod
    def normalize_conversation_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None


class QueryResultItem(CamelModel):
    """单条 Query 执行结果，按状态强制 payload 是否存在。"""

    module_id: str = Field(min_length=1)
    query_id: str = Field(min_length=1)
    status: QUERY_RESULT_STATUS
    payload: Any | None = None
    message: str = ""

    @model_validator(mode="after")
    def validate_payload_contract(self) -> "QueryResultItem":
        """保证成功结果可被模型消费，失败或截断结果不夹带半可信数据。"""
        if self.status == "OK" and not isinstance(self.payload, (dict, list)):
            raise ValueError("OK 查询结果的 payload 必须是对象或数组")
        if self.status != "OK" and self.payload is not None:
            raise ValueError("FAILED 或 TRUNCATED 查询结果不得携带 payload")
        return self


class QueryResultsRequest(CamelModel):
    """Query 结果续聊请求。

    Query 结果始终进入受控续聊；服务层仍需检查 UTF-8 大小上限，避免把
    超出模型上下文边界的本地数据发送出去。
    """

    goal: str = Field(min_length=1, max_length=4000)
    conversation_id: str = Field(min_length=1, max_length=200)
    client_request_id: str = Field(min_length=1, max_length=200)
    ai_plan_enabled: bool
    modules: list[ToolModule] = Field(default_factory=list)
    query_results: list[QueryResultItem] = Field(min_length=1, max_length=4)

    @field_validator("goal", "conversation_id", "client_request_id")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("不能为空")
        return normalized


class ActionCall(CamelModel):
    """模型提出的 Action 引用，不允许携带额外执行控制字段。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    module_id: str = Field(min_length=1)
    action_id: str = Field(min_length=1)
    arguments: dict[str, Any] = Field(default_factory=dict)


class QueryCall(CamelModel):
    """模型提出的只读 Query 引用，执行与权限校验不在 Python 侧。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    module_id: str = Field(min_length=1)
    query_id: str = Field(min_length=1)
    arguments: dict[str, Any] = Field(default_factory=dict)


class Question(CamelModel):
    """目标或参数不充分时返回给用户的澄清问题。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    prompt: str = Field(min_length=1)
    options: list[str] = Field(default_factory=list)


class ViewField(CamelModel):
    """展示描述中引用的单个已有字段。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    key: str = Field(min_length=1, max_length=160)
    label: str = Field(min_length=1, max_length=80)
    format: VIEW_FORMAT
    role: VIEW_ROLE | None = None


class QueryView(CamelModel):
    """AI 返回给前端的受控展示说明书，不包含业务数据。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    source_query: int = Field(ge=1, le=4)
    layout: VIEW_LAYOUT
    title: str = Field(min_length=1, max_length=200)
    row_path: str | None = Field(default=None, max_length=200)
    fields: list[ViewField] = Field(max_length=12)
    detail_fields: list[ViewField] = Field(default_factory=list, max_length=12)
    empty_text: str | None = Field(default=None, max_length=200)
    density: VIEW_DENSITY = "comfortable"
    group_by: VIEW_GROUP_BY = "none"
    hide_repeated_fields: bool = False
    emphasis_fields: list[str] = Field(default_factory=list, max_length=2)


class Decision(CamelModel):
    """Python 到 Java 的五种决策线格式模型。

    ``ACTIONS`` 与 ``PLAN`` 共用 actions，``QUERY`` 使用 queries，
    ``QUESTION`` 使用 question，``VIEW`` 使用 view；解析和归一化阶段会拒绝
    跨 kind 混用字段。
    ``plan`` 保持为空，防止嵌套计划绕过统一 Action 数组契约。Java 仍会对
    序列化结果执行最终校验。
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    kind: DECISION_KIND
    conversation_id: str | None = Field(default=None, max_length=200)
    message: str = ""
    actions: list[ActionCall] | None = None
    plan: None = None
    question: Question | None = None
    queries: list[QueryCall] | None = None
    view: QueryView | None = None


class ErrorView(CamelModel):
    """服务错误响应；字段内容必须是安全、稳定且可审计的。"""

    code: str
    message: str
    timestamp: str
    details: dict[str, Any] | None = None


@dataclass(frozen=True, slots=True)
class ProviderConfig:
    """一次模型调用使用的不可变设置快照。

    Key 只保存在该对象的进程内存中。``version`` 用于使设置变更前的会话、
    重放结果和正在进行的请求失效，避免新旧安全策略混用。
    """

    mode: Literal["DEEPSEEK", "CUSTOM"]
    base_url: str
    model: str
    api_key: str | None
    allow_delete_direct: bool
    version: int

    @property
    def configured(self) -> bool:
        """只暴露是否已配置，不暴露 Key 的内容或长度。"""
        return bool(self.api_key)

    def __repr__(self) -> str:
        """生成诊断安全的表示，确保日志和异常不会带出 API Key。"""
        key_state = "[redacted]" if self.configured else None
        return (
            "ProviderConfig("
            f"mode={self.mode!r}, base_url={self.base_url!r}, model={self.model!r}, "
            f"api_key={key_state!r}, "
            f"allow_delete_direct={self.allow_delete_direct!r}, "
            f"version={self.version!r})"
        )
