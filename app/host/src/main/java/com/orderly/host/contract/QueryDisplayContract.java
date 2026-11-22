package com.orderly.host.contract;

import java.util.List;

/**
 * 模块为只读 Query 结果声明的展示建议。
 *
 * <p>提示词只用于指导受控展示，不改变 Query payload、授权或执行语义。
 * 所有字段在模块加载阶段完成静态校验。</p>
 */
public record QueryDisplayContract(
        String prompt,
        String fallbackLayout,
        String rowPath,
        List<Field> fields,
        String emptyText
) {
    public QueryDisplayContract {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** 单个可展示字段。 */
    public record Field(
            String key,
            String label,
            String format
    ) {
    }
}
