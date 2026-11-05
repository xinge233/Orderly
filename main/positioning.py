"""接口测试代理的定位与操作边界。

本模块只定义和校验边界，不执行文件增删改、网络请求或接口调用。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Final, Literal


GITHUB_REPOSITORY: Final = "xinge233/Orderly"
GITHUB_URL: Final = "https://github.com/xinge233/Orderly"
DEFAULT_BRANCH: Final = "main"
PUBLISH_BRANCH: Final = "interface-testing-active"
FORBIDDEN_BRANCHES: Final = frozenset({"main"})

LocalOperation = Literal["read", "write", "test"]
RemoteOperation = Literal["read", "write"]


class ScopeViolation(PermissionError):
    """请求超出了接口测试代理的授权范围。"""


@dataclass(frozen=True, slots=True)
class AgentPosition:
    """接口测试代理的静态定位。"""

    scope_root: Path
    agents_file: Path = Path("AGENTS.md")
    repository: str = GITHUB_REPOSITORY
    repository_url: str = GITHUB_URL
    default_branch: str = DEFAULT_BRANCH
    role: str = "draft-interface-testing-agent"

    def __post_init__(self) -> None:
        # 统一解析工作区根目录，避免相对路径中的 `..` 或符号链接绕过边界。
        object.__setattr__(self, "scope_root", self.scope_root.resolve())

    @property
    def main_directory(self) -> Path:
        """返回接口测试主程序目录，程序文件只能存放在该目录下。"""

        return self.scope_root / "main"

    def resolve_local_path(self, target: str | Path) -> Path:
        """将相对路径按测试目录解析，并消除 `..` 与符号链接。"""

        path = Path(target)
        if not path.is_absolute():
            path = self.scope_root / path
        return path.resolve(strict=False)

    def require_local_path(self, target: str | Path) -> Path:
        """校验目标位于测试目录内，返回规范化后的绝对路径。"""

        resolved = self.resolve_local_path(target)
        if resolved != self.scope_root and self.scope_root not in resolved.parents:
            raise ScopeViolation(
                f"路径越界：{resolved} 不在授权目录 {self.scope_root} 内"
            )
        return resolved

    def require_local_operation(
        self,
        operation: LocalOperation,
        target: str | Path,
    ) -> Path:
        """只允许在测试目录内进行读取、写入和测试。"""

        if operation not in {"read", "write", "test"}:
            raise ScopeViolation(f"不支持的本地操作：{operation}")
        return self.require_local_path(target)

    def require_remote_operation(
        self,
        operation: RemoteOperation,
        *,
        branch: str,
        explicitly_authorized: bool = False,
    ) -> None:
        """只允许操作专用分支，并禁止触碰任何受保护分支。"""

        # 分支边界必须先于读写权限判断，防止任何操作误入主分支。
        if branch in FORBIDDEN_BRANCHES:
            raise ScopeViolation(f"禁止对受保护分支执行任何操作：{branch}")
        if branch != PUBLISH_BRANCH:
            raise ScopeViolation(
                f"GitHub 操作仅允许针对分支 {PUBLISH_BRANCH}，当前为：{branch}"
            )

        # 读取专用分支始终允许；写入仍要求用户对本次动作明确授权。
        if operation == "read":
            return
        if operation == "write" and explicitly_authorized:
            return
        raise ScopeViolation("GitHub 写操作需要用户明确授权")

    def require_agents_file(self) -> Path:
        """确认授权目录内的 AGENTS.md 存在且可读。"""

        path = self.require_local_path(self.agents_file)
        if not path.is_file():
            raise ScopeViolation(f"协作规则文件不存在：{path}")
        path.read_text(encoding="utf-8")
        return path

    def as_summary(self) -> dict[str, object]:
        """返回适合日志或接口测试断言的定位摘要。"""

        return {
            "role": self.role,
            "scope_root": str(self.scope_root),
            "main_directory": str(self.main_directory),
            "agents_file": str(self.require_agents_file()),
            "local_operations": ["read", "write", "test"],
            "remote_operations": {
                "branch": PUBLISH_BRANCH,
                "read": "allowed-on-designated-branch",
                "write": "requires-explicit-authorization-on-designated-branch",
                "forbidden_branches": sorted(FORBIDDEN_BRANCHES),
            },
            "repository": self.repository,
            "repository_url": self.repository_url,
            "default_branch": self.default_branch,
            "publish_branch": PUBLISH_BRANCH,
        }


# 该文件位于 main 目录，因此向上一级才是工作区根目录。
POSITION: Final = AgentPosition(scope_root=Path(__file__).resolve().parent.parent)


if __name__ == "__main__":
    import json

    print(json.dumps(POSITION.as_summary(), ensure_ascii=False, indent=2))
