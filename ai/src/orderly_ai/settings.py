"""进程内存中的 AI Provider 设置与 URL 安全规范化。

设置不得写入仓库、普通配置、浏览器存储或日志。DeepSeek 模式固定官方
Base URL 与模型标识；自定义模式默认只允许 HTTPS，只有显式开发开关存在时
才允许回环地址使用 HTTP。每次变更递增 ``version``，使旧会话、重放结果和
并发中的回合失效。
"""

from __future__ import annotations

import os
import threading
from urllib.parse import urlsplit, urlunsplit

from .errors import ServiceError
from .models import ProviderConfig, SettingsPutRequest, SettingsView


DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEEPSEEK_MODEL = "deepseek-flash"


class SettingsStore:
    """线程安全的内存设置存储，API Key 永不进入公开视图或序列化结果。"""

    def __init__(self, allow_local_provider: bool | None = None) -> None:
        """建立未配置的 DeepSeek 默认状态。

        锁用于同步设置读写；初始 Key 为空，只有 ``configure`` 才可把调用方
        临时提交的 Key 放入当前进程内存。
        """
        self._lock = threading.RLock()
        self._allow_local_provider = (
            os.environ.get("ORDERLY_AI_ALLOW_LOCAL_PROVIDER", "")
            .strip()
            .lower()
            in {"1", "true", "yes", "on"}
            if allow_local_provider is None
            else allow_local_provider
        )
        self._config = ProviderConfig(
            mode="DEEPSEEK",
            base_url=DEEPSEEK_BASE_URL,
            model=DEEPSEEK_MODEL,
            api_key=None,
            allow_delete_direct=False,
            allow_send_query_results_to_model=False,
            version=0,
        )

    def snapshot(self) -> ProviderConfig:
        """返回不可变设置快照，供一次请求一致地读取开关与 Key。"""
        with self._lock:
            return self._config

    def view(self) -> SettingsView:
        """返回不含 Key 的公开设置视图。"""
        return self._view(
            self.snapshot(),
            allow_local_provider=self._allow_local_provider,
        )

    def require_configured(self) -> ProviderConfig:
        """取得可用快照；未配置 Key 时以稳定错误失败关闭。"""
        config = self.snapshot()
        if not config.configured:
            raise ServiceError(
                409,
                "AI_NOT_CONFIGURED",
                "AI 尚未接入，请先输入 API Key。",
            )
        return config

    def configure(self, request: SettingsPutRequest) -> SettingsView:
        """应用局部设置更新并递增安全策略代次。

        空 ``apiKey`` 会被模型归一化为 ``None``，表示保留内存中的旧 Key；
        只有 ``clear`` 会主动移除 Key。模式固定值和 URL 校验在持锁期间完成，
        避免读取到半更新配置。
        """
        with self._lock:
            current = self._config
            mode = request.mode or current.mode
            api_key = request.api_key or current.api_key
            if not api_key:
                raise ServiceError(
                    400,
                    "AI_API_KEY_REQUIRED",
                    "请输入 API Key。",
                )

            allow_delete_direct = (
                current.allow_delete_direct
                if request.allow_delete_direct is None
                else request.allow_delete_direct
            )
            allow_query_results = (
                current.allow_send_query_results_to_model
                if request.allow_send_query_results_to_model is None
                else request.allow_send_query_results_to_model
            )

            if mode == "DEEPSEEK":
                base_url = DEEPSEEK_BASE_URL
                model = DEEPSEEK_MODEL
            else:
                base_url_source = request.base_url
                if not base_url_source and current.mode != "CUSTOM":
                    raise ServiceError(
                        400,
                        "AI_BASE_URL_REQUIRED",
                        "自定义模式需要 API Base URL。",
                    )
                model_source = request.model
                if not model_source and current.mode != "CUSTOM":
                    raise ServiceError(
                        400,
                        "AI_MODEL_REQUIRED",
                        "自定义模式需要 Model。",
                    )
                base_url = normalize_base_url(
                    base_url_source if base_url_source else current.base_url,
                    allow_local_provider=self._allow_local_provider,
                )
                model = normalize_model(
                    model_source if model_source else current.model
                )

            self._config = ProviderConfig(
                mode=mode,
                base_url=base_url,
                model=model,
                api_key=api_key,
                allow_delete_direct=allow_delete_direct,
                allow_send_query_results_to_model=allow_query_results,
                version=current.version + 1,
            )
            return self._view(
                self._config,
                allow_local_provider=self._allow_local_provider,
            )

    def clear(self) -> SettingsView:
        """清除 Key 和安全开关，并恢复 DeepSeek 默认配置。"""
        with self._lock:
            self._config = ProviderConfig(
                mode="DEEPSEEK",
                base_url=DEEPSEEK_BASE_URL,
                model=DEEPSEEK_MODEL,
                api_key=None,
                allow_delete_direct=False,
                allow_send_query_results_to_model=False,
                version=self._config.version + 1,
            )
            return self._view(
                self._config,
                allow_local_provider=self._allow_local_provider,
            )

    @staticmethod
    def _view(
        config: ProviderConfig,
        *,
        allow_local_provider: bool,
    ) -> SettingsView:
        """把内部配置投影为可跨 IPC 返回的安全模型。"""
        return SettingsView(
            mode=config.mode,
            base_url=config.base_url,
            model=config.model,
            configured=config.configured,
            allow_delete_direct=config.allow_delete_direct,
            allow_send_query_results_to_model=(
                config.allow_send_query_results_to_model
            ),
            allow_local_provider=allow_local_provider,
            settings_version=config.version,
        )


def normalize_model(value: str) -> str:
    """规范化自定义模型标识，拒绝空值。"""
    model = value.strip()
    if not model:
        raise ServiceError(
            400,
            "AI_MODEL_REQUIRED",
            "自定义模式需要 Model。",
        )
    return model


def normalize_base_url(
    value: str,
    *,
    allow_local_provider: bool = False,
) -> str:
    """校验并规范化自定义 OpenAI 兼容 Base URL。

    禁止 URL 内嵌用户信息、查询参数和片段，避免凭据泄露或端点解析歧义。
    HTTPS 是默认要求；localhost HTTP 仅在显式开发开关开启时放行。
    """
    raw = value.strip().rstrip("/")
    if not raw:
        raise invalid_url("自定义模式需要 API Base URL。")
    if any(character.isspace() for character in raw):
        raise invalid_url("API Base URL 格式非法。")

    try:
        parsed = urlsplit(raw)
        port = parsed.port
    except ValueError as exc:
        raise invalid_url("API Base URL 格式非法。") from exc

    scheme = parsed.scheme.lower()
    local_scheme_allowed = (
        allow_local_provider
        and scheme == "http"
        and parsed.hostname in {"127.0.0.1", "::1", "localhost"}
    )
    if scheme != "https" and not local_scheme_allowed:
        raise invalid_url(
            "自定义 API 只允许 HTTPS，或开发开关下的 localhost HTTP。"
            if allow_local_provider
            else "自定义 API 必须使用 HTTPS。"
        )
    if not parsed.hostname or parsed.username is not None or parsed.password is not None:
        raise invalid_url("API Base URL 必须只包含协议、主机和可选路径。")
    if parsed.query or parsed.fragment:
        raise invalid_url("API Base URL 不能包含查询参数或片段。")
    if port is None and parsed.netloc.endswith(":"):
        raise invalid_url("API Base URL 格式非法。")
    if not parsed.path.startswith("/") and parsed.path:
        raise invalid_url("API Base URL 格式非法。")

    normalized_path = parsed.path.rstrip("/")
    return urlunsplit((scheme, parsed.netloc, normalized_path, "", ""))


def invalid_url(message: str) -> ServiceError:
    """构造稳定的 Base URL 校验错误。"""
    return ServiceError(400, "AI_INVALID_BASE_URL", message)
