"""Orderly AI 的内部错误类型及其安全对外映射。

内部异常可以携带实现上下文，但只有 ``ServiceError`` 会直接决定 HTTP 状态、
稳定错误码和安全消息。模型 Provider 的原始响应、请求头和凭据不得经由这些
错误进入 IPC 响应或日志。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class ServiceError(Exception):
    """可安全返回给 Java 宿主的受控服务错误。"""

    status_code: int
    code: str
    message: str
    details: dict[str, Any] = field(default_factory=dict)

    def __str__(self) -> str:
        return self.message


class DecisionError(ValueError):
    """模型输出不符合受控决策契约时使用的内部异常。"""


class ProviderError(RuntimeError):
    """模型 Provider 失败；消息必须经过挑选且不得包含秘密或上游正文。"""


def invalid_request(message: str) -> ServiceError:
    """构造调用方可修正的请求错误。"""
    return ServiceError(400, "INVALID_REQUEST", message)


def invalid_response(message: str) -> ServiceError:
    """构造模型或 IPC 响应不符合契约时的上游错误。"""
    return ServiceError(502, "AI_INVALID_RESPONSE", message)


def provider_unavailable() -> ServiceError:
    """把各类 Provider 故障折叠为不泄露细节的稳定错误。"""
    return ServiceError(
        502,
        "AI_PROVIDER_UNAVAILABLE",
        "模型服务暂时不可用，请稍后重试。",
    )
