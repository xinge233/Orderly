package com.orderly.host.contract;

/**
 * 契约中的参数声明。
 *
 * @param name 参数名
 * @param type string / boolean / integer
 * @param required 是否必填
 * @param description 说明
 */
public record ParameterContract(
        String name,
        String type,
        boolean required,
        String description
) {
}
