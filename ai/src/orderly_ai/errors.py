from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class ServiceError(Exception):
    status_code: int
    code: str
    message: str
    details: dict[str, Any] = field(default_factory=dict)

    def __str__(self) -> str:
        return self.message


class DecisionError(ValueError):
    """Raised when model output does not match the controlled decision contract."""


class ProviderError(RuntimeError):
    """A provider failure with a deliberately safe, non-secret message."""


def invalid_request(message: str) -> ServiceError:
    return ServiceError(400, "INVALID_REQUEST", message)


def invalid_response(message: str) -> ServiceError:
    return ServiceError(502, "AI_INVALID_RESPONSE", message)


def provider_unavailable() -> ServiceError:
    return ServiceError(
        502,
        "AI_PROVIDER_UNAVAILABLE",
        "模型服务暂时不可用，请稍后重试。",
    )
