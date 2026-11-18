package com.orderly.host.contract;

import java.util.List;

/**
 * 模块自有只读查询契约。
 *
 * @param id 全局查询 ID
 * @param title 显示名称
 * @param description 说明
 * @param parameters 查询参数
 */
public record QueryContract(
        String id,
        String title,
        String description,
        List<ParameterContract> parameters
) {
    public QueryContract {
        parameters = List.copyOf(parameters);
    }
}
