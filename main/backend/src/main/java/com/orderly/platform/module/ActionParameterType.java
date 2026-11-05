package com.orderly.platform.module;

/**
 * 受控动作允许使用的最小参数类型集合。
 *
 * <p>这里刻意不使用任意对象或脚本表达式，避免模块把未经限制的数据
 * 直接交给文件系统、Shell 或外部服务。</p>
 */
public enum ActionParameterType {
    STRING,
    INTEGER,
    BOOLEAN
}
