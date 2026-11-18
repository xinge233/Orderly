from __future__ import annotations

import hmac
import os
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .errors import ServiceError
from .models import (
    ConnectionTestView,
    Decision,
    ErrorView,
    QueryResultsRequest,
    SettingsPutRequest,
    SettingsView,
    TurnRequest,
)
from .provider import OpenAICompatibleProvider
from .service import AIService, Provider


def create_app(
    *,
    ipc_token: str | None = None,
    provider: Provider | None = None,
    service: AIService | None = None,
    monotonic: Callable[[], float] | None = None,
    local_now: Callable[[], datetime] | None = None,
) -> FastAPI:
    expected_token = (
        os.environ.get("ORDERLY_AI_IPC_TOKEN", "")
        if ipc_token is None
        else ipc_token
    )
    resolved_provider = provider or OpenAICompatibleProvider()
    ai_service = service or AIService(
        provider=resolved_provider,
        monotonic=monotonic or __import__("time").monotonic,
        local_now=local_now,
    )

    app = FastAPI(
        title="Orderly AI",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.ai_service = ai_service
    app.state.ipc_token = expected_token

    @app.exception_handler(ServiceError)
    async def service_error_handler(
        request: Request,
        exception: ServiceError,
    ) -> JSONResponse:
        return _error_response(
            exception.status_code,
            exception.code,
            exception.message,
            exception.details,
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        request: Request,
        exception: RequestValidationError,
    ) -> JSONResponse:
        errors = []
        for error in exception.errors():
            errors.append(
                {
                    "loc": list(error.get("loc", [])),
                    "msg": str(error.get("msg", "字段校验失败")),
                    "type": str(error.get("type", "value_error")),
                }
            )
        return _error_response(
            400,
            "INVALID_REQUEST",
            "请求字段校验失败。",
            {"errors": errors},
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(
        request: Request,
        exception: StarletteHTTPException,
    ) -> JSONResponse:
        code = "NOT_FOUND" if exception.status_code == 404 else "HTTP_ERROR"
        message = (
            "请求的资源不存在。"
            if exception.status_code == 404
            else "请求处理失败。"
        )
        return _error_response(exception.status_code, code, message)

    @app.exception_handler(Exception)
    async def unexpected_error_handler(
        request: Request,
        exception: Exception,
    ) -> JSONResponse:
        return _error_response(
            500,
            "INTERNAL_ERROR",
            "服务处理失败。",
        )

    async def require_token(
        authorization: str | None = Header(default=None),
    ) -> None:
        if not expected_token:
            raise ServiceError(
                503,
                "AI_IPC_TOKEN_NOT_CONFIGURED",
                "AI IPC 鉴权令牌尚未配置。",
            )
        scheme, separator, supplied = (authorization or "").partition(" ")
        if (
            not separator
            or scheme.lower() != "bearer"
            or not supplied
            or not hmac.compare_digest(supplied, expected_token)
        ):
            raise ServiceError(
                401,
                "UNAUTHORIZED",
                "需要有效的 Bearer 鉴权。",
            )

    @app.get("/v1/health")
    async def health() -> dict[str, str]:
        return {"status": "UP"}

    protected = [Depends(require_token)]

    @app.get("/v1/settings", response_model=SettingsView, dependencies=protected)
    async def get_settings() -> SettingsView:
        return ai_service.get_settings()

    @app.put("/v1/settings", response_model=SettingsView, dependencies=protected)
    async def put_settings(request: SettingsPutRequest) -> SettingsView:
        return ai_service.put_settings(request)

    @app.delete(
        "/v1/settings",
        response_model=SettingsView,
        dependencies=protected,
    )
    async def delete_settings() -> SettingsView:
        return ai_service.delete_settings()

    @app.post(
        "/v1/settings/test",
        response_model=ConnectionTestView,
        dependencies=protected,
    )
    async def test_settings() -> ConnectionTestView:
        return await ai_service.test_connection()

    @app.post(
        "/v1/turns",
        response_model=Decision,
        response_model_exclude_none=True,
        dependencies=protected,
    )
    async def create_turn(request: TurnRequest) -> Decision:
        return await ai_service.turn(request)

    @app.post(
        "/v1/turns/query-results",
        response_model=Decision,
        response_model_exclude_none=True,
        dependencies=protected,
    )
    async def submit_query_results(request: QueryResultsRequest) -> Decision:
        return await ai_service.query_results(request)

    return app


def _error_response(
    status_code: int,
    code: str,
    message: str,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    body = ErrorView(
        code=code,
        message=message,
        timestamp=datetime.now(UTC).isoformat(timespec="milliseconds").replace(
            "+00:00",
            "Z",
        ),
        details=details or None,
    )
    headers = (
        {"WWW-Authenticate": "Bearer"}
        if status_code == 401
        else None
    )
    return JSONResponse(
        status_code=status_code,
        content=body.model_dump(
            by_alias=True,
            exclude_none=True,
        ),
        headers=headers,
    )
