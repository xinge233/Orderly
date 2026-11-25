"""启动仅监听回环地址的 Orderly AI 服务进程。

启动脚本负责生成并注入短暂 IPC 令牌。本模块只接受端口配置，不读取或记录
模型 API Key，也不允许把监听地址扩展到局域网或公网接口。
"""

from __future__ import annotations

import argparse
import os
import runpy
import sys

import uvicorn

from .app import create_app


def main() -> None:
    """校验进程级启动参数后运行 Uvicorn。

    回环监听是 IPC 信任边界的一部分；缺少随机 Bearer 令牌时直接失败关闭，
    避免受保护端点以无鉴权状态启动。访问日志关闭，防止请求头或临时凭据
    意外进入终端日志。
    """
    parser = argparse.ArgumentParser(description="Run the loopback Orderly AI service")
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("ORDERLY_AI_PORT", "8091")),
    )
    parser.add_argument(
        "--module-script",
        default="",
        help="Run a trusted module-local AI script as a one-shot process.",
    )
    args = parser.parse_args()
    if args.module_script:
        script = os.path.abspath(args.module_script)
        if not os.path.isfile(script):
            parser.error("--module-script 必须指向现有脚本")
        runpy.run_path(script, run_name="__main__")
        return
    if not 1 <= args.port <= 65535:
        parser.error("--port 必须在 1 到 65535 之间")
    if not os.environ.get("ORDERLY_AI_IPC_TOKEN"):
        parser.error("必须设置 ORDERLY_AI_IPC_TOKEN")

    uvicorn.run(
        create_app(),
        host="127.0.0.1",
        port=args.port,
        log_level="info",
        access_log=False,
    )


if __name__ == "__main__":
    main()
