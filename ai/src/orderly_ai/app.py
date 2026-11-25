"""Orderly AI 的 FastAPI 应用工厂、IPC 鉴权和稳定错误外观。

服务由 Java 宿主作为本机子进程管理，只监听 ``127.0.0.1``。除健康探针外，
所有端点都要求每次运行随机生成的 Bearer Token。API Key 属于 Provider
设置而非 IPC 凭据：它只存在 Python 进程内存中，任何响应和日志都不得包含。
"""

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
    ModelCompletionRequest,
    ModelCompletionView,
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
    """组装 AI 服务及其受保护 IPC 路由。

    生产启动从环境读取 ``ORDERLY_AI_IPC_TOKEN``；测试可注入 token、Provider、
    服务和时钟。未配置 token 时受保护端点失败关闭。OpenAPI 与文档端点关闭，
    避免扩大本机服务的可探测面。
    """
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
        """返回受控业务错误，并保留调用方可安全处理的状态与错误码。"""
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
        """把 Pydantic 校验失败折叠为稳定结构。"""
        errors = []
        for error in exception.errors():
            # 只保留位置、原因和类型，避免把原始输入值写回响应。
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
        """隐藏框架默认错误正文，只暴露稳定状态和通用消息。"""
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
        """把未预期异常折叠为 500，避免秘密或上游内容进入响应。"""
        return _error_response(
            500,
            "INTERNAL_ERROR",
            "服务处理失败。",
        )

    async def require_token(
        authorization: str | None = Header(default=None),
    ) -> None:
        """执行大小写不敏感的 Bearer 校验，并使用常量时间比较秘密。"""
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
        """提供无副作用的存活探针，不暴露设置、模型或 IPC 秘密。"""
        return {"status": "UP"}

    protected = [Depends(require_token)]

    @app.get("/v1/settings", response_model=SettingsView, dependencies=protected)
    async def get_settings() -> SettingsView:
        """读取安全设置投影。"""
        return ai_service.get_settings()

    @app.put("/v1/settings", response_model=SettingsView, dependencies=protected)
    async def put_settings(request: SettingsPutRequest) -> SettingsView:
        """更新进程内存设置并使旧状态代次失效。"""
        return ai_service.put_settings(request)

    @app.delete(
        "/v1/settings",
        response_model=SettingsView,
        dependencies=protected,
    )
    async def delete_settings() -> SettingsView:
        """清除内存 Key 并恢复 DeepSeek 默认策略。"""
        return ai_service.delete_settings()

    @app.post(
        "/v1/settings/test",
        response_model=ConnectionTestView,
        dependencies=protected,
    )
    async def test_settings() -> ConnectionTestView:
        """使用当前内存 Key 发起最小 Provider 连通性测试。"""
        return await ai_service.test_connection()

    @app.post(
        "/v1/model/complete",
        response_model=ModelCompletionView,
        dependencies=protected,
    )
    async def complete_model(
        request: ModelCompletionRequest,
    ) -> ModelCompletionView:
        """执行无状态通用补全；业务提示词与文件能力不在根 AI 内。"""
        return await ai_service.complete_model(request)

    @app.post(
        "/v1/turns",
        response_model=Decision,
        response_model_exclude_none=True,
        dependencies=protected,
    )
    async def create_turn(request: TurnRequest) -> Decision:
        """处理一次目标回合，可返回包含 Query 的首轮决策。"""
        return await ai_service.turn(request)

    @app.post(
        "/v1/turns/query-results",
        response_model=Decision,
        response_model_exclude_none=True,
        dependencies=protected,
    )
    async def submit_query_results(request: QueryResultsRequest) -> Decision:
        """提交经用户同意且已限长的 Query 结果以继续决策。"""
        return await ai_service.query_results(request)

    return app


def _error_response(
    status_code: int,
    code: str,
    message: str,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    """生成统一错误体，并为 401 附加标准 Bearer challenge。"""
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
