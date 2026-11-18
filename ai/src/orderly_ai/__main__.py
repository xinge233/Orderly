from __future__ import annotations

import argparse
import os

import uvicorn

from .app import create_app


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the loopback Orderly AI service")
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("ORDERLY_AI_PORT", "8091")),
    )
    args = parser.parse_args()
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
