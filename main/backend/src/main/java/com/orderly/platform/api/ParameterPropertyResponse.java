package com.orderly.platform.api;

/**
 * 公共参数 schema 中的单个属性。
 *
 * @param name 参数名
 * @param description 参数说明
 * @param type 受控参数类型
 * @param required 是否必填
 */
public record ParameterPropertyResponse(
        String name,
        String description,
        String type,
        boolean required
) {
}
