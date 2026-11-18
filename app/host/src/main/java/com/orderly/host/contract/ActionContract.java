package com.orderly.host.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.orderly.kernel.RiskLevel;

import java.util.List;

/**
 * 模块动作契约。
 *
 * @param id 全局动作 ID
 * @param title 显示名称
 * @param description 说明
 * @param risk 风险等级
 * @param confirmationRequired 是否要求计划确认
 * @param confirmationPhrase 旧契约兼容字段；当前不再要求删除动作声明确认词
 * @param parameters 参数
 * @param outputSchema 可选的结构化输出 JSON Schema 子集
 */
public record ActionContract(
        String id,
        String title,
        String description,
        RiskLevel risk,
        boolean confirmationRequired,
        String confirmationPhrase,
        List<ParameterContract> parameters,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode outputSchema
) {
    public ActionContract {
        parameters = List.copyOf(parameters);
        outputSchema = outputSchema == null || outputSchema.isNull() ? null : outputSchema.deepCopy();
    }
}
