package com.orderly.host.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 模块自有只读查询契约。
 *
 * @param id 全局查询 ID
 * @param title 显示名称
 * @param description 说明
 * @param parameters 查询参数
 * @param outputSchema 可选的结果结构声明
 * @param display 可选的展示建议
 */
public record QueryContract(
        String id,
        String title,
        String description,
        List<ParameterContract> parameters,
        JsonNode outputSchema,
        QueryDisplayContract display
) {
    public QueryContract {
        parameters = List.copyOf(parameters);
    }

    /** 兼容尚未声明结果结构和展示建议的旧模块与测试。 */
    public QueryContract(
            String id,
            String title,
            String description,
            List<ParameterContract> parameters
    ) {
        this(id, title, description, parameters, null, null);
    }
}
