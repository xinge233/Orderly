"""Orderly 本机 AI 决策服务包。

该包只负责把用户目标、工具卡与短时会话转换为受控决策，不持有文件或模块
执行权限。对外入口仅暴露应用工厂；网络监听、IPC 鉴权和凭据边界分别由
``__main__``、``app`` 与 ``settings`` 负责。
"""

from .app import create_app

__all__ = ["create_app"]
