package com.orderly.host.contract;

/**
 * 从模块 JAR 读取的 UI 静态资源。
 *
 * @param contentType HTTP 内容类型
 * @param content 原始字节
 */
public record ModuleUiAsset(String contentType, byte[] content) {
}
