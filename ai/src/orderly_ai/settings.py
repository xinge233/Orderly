from __future__ import annotations

import os
import threading
from urllib.parse import urlsplit, urlunsplit

from .errors import ServiceError
from .models import ProviderConfig, SettingsPutRequest, SettingsView


DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEEPSEEK_MODEL = "deepseek-flash"


class SettingsStore:
    """Process-memory AI settings. The API key is never serialized."""

    def __init__(self, allow_local_provider: bool | None = None) -> None:
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
        with self._lock:
            return self._config

    def view(self) -> SettingsView:
        return self._view(
            self.snapshot(),
            allow_local_provider=self._allow_local_provider,
        )

    def require_configured(self) -> ProviderConfig:
        config = self.snapshot()
        if not config.configured:
            raise ServiceError(
                409,
                "AI_NOT_CONFIGURED",
                "AI 尚未接入，请先输入 API Key。",
            )
        return config

    def configure(self, request: SettingsPutRequest) -> SettingsView:
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
    return ServiceError(400, "AI_INVALID_BASE_URL", message)
