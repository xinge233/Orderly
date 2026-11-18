from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


RISK_LEVEL = Literal["READ_ONLY", "WRITE", "DELETE"]
DECISION_KIND = Literal["ACTIONS", "PLAN", "QUESTION", "QUERY"]
QUERY_RESULT_STATUS = Literal["OK", "FAILED", "TRUNCATED"]


def to_camel(value: str) -> str:
    first, *remaining = value.split("_")
    return first + "".join(part.capitalize() for part in remaining)


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        use_enum_values=True,
    )


class SettingsPutRequest(CamelModel):
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
    allow_send_query_results_to_model: bool | None = None

    @field_validator("api_key")
    @classmethod
    def normalize_api_key(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None


class SettingsView(CamelModel):
    mode: Literal["DEEPSEEK", "CUSTOM"]
    base_url: str
    model: str
    configured: bool
    allow_delete_direct: bool
    allow_send_query_results_to_model: bool
    allow_local_provider: bool = False
    settings_version: int


class ConnectionTestView(CamelModel):
    ok: bool
    message: str


class ParameterCard(CamelModel):
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


class QueryCard(CamelModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    query_id: str = Field(min_length=1)
    title: str = ""
    description: str = ""
    parameters: list[ParameterCard] = Field(default_factory=list)


class ToolModule(CamelModel):
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
    module_id: str = Field(min_length=1)
    query_id: str = Field(min_length=1)
    status: QUERY_RESULT_STATUS
    payload: Any | None = None
    message: str = ""

    @model_validator(mode="after")
    def validate_payload_contract(self) -> "QueryResultItem":
        if self.status == "OK" and not isinstance(self.payload, (dict, list)):
            raise ValueError("OK 查询结果的 payload 必须是对象或数组")
        if self.status != "OK" and self.payload is not None:
            raise ValueError("FAILED 或 TRUNCATED 查询结果不得携带 payload")
        return self


class QueryResultsRequest(CamelModel):
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
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    module_id: str = Field(min_length=1)
    action_id: str = Field(min_length=1)
    arguments: dict[str, Any] = Field(default_factory=dict)


class QueryCall(CamelModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    module_id: str = Field(min_length=1)
    query_id: str = Field(min_length=1)
    arguments: dict[str, Any] = Field(default_factory=dict)


class Question(CamelModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    prompt: str = Field(min_length=1)
    options: list[str] = Field(default_factory=list)


class Decision(CamelModel):
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


class ErrorView(CamelModel):
    code: str
    message: str
    timestamp: str
    details: dict[str, Any] | None = None


@dataclass(frozen=True, slots=True)
class ProviderConfig:
    mode: Literal["DEEPSEEK", "CUSTOM"]
    base_url: str
    model: str
    api_key: str | None
    allow_delete_direct: bool
    allow_send_query_results_to_model: bool
    version: int

    @property
    def configured(self) -> bool:
        return bool(self.api_key)

    def __repr__(self) -> str:
        key_state = "[redacted]" if self.configured else None
        return (
            "ProviderConfig("
            f"mode={self.mode!r}, base_url={self.base_url!r}, model={self.model!r}, "
            f"api_key={key_state!r}, "
            f"allow_delete_direct={self.allow_delete_direct!r}, "
            "allow_send_query_results_to_model="
            f"{self.allow_send_query_results_to_model!r}, version={self.version!r})"
        )
