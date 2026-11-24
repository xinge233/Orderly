package com.orderly.host.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 模块为只读 Query 结果声明的展示建议。
 *
 * <p>提示词只用于指导受控展示，不改变 Query payload、授权或执行语义。
 * 所有字段在模块加载阶段完成静态校验。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryDisplayContract(
        String prompt,
        String fallbackLayout,
        String rowPath,
        List<Field> fields,
        String emptyText,
        String density,
        String groupBy,
        boolean hideRepeatedFields,
        List<Field> detailFields,
        List<String> emphasisFields
) {
    public QueryDisplayContract {
        fields = fields == null ? List.of() : List.copyOf(fields);
        detailFields = detailFields == null ? List.of() : List.copyOf(detailFields);
        emphasisFields = emphasisFields == null ? List.of() : List.copyOf(emphasisFields);
    }

    /** 兼容尚未声明扩展展示语义的模块契约。 */
    public QueryDisplayContract(
            String prompt,
            String fallbackLayout,
            String rowPath,
            List<Field> fields,
            String emptyText
    ) {
        this(
                prompt,
                fallbackLayout,
                rowPath,
                fields,
                emptyText,
                "comfortable",
                "none",
                false,
                List.of(),
                List.of()
        );
    }

    /** 单个可展示字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(
            String key,
            String label,
            String format,
            String role,
            Map<String, String> valueLabels
    ) {
        public Field {
            valueLabels = valueLabels == null ? Map.of() : Map.copyOf(valueLabels);
        }

        /** 兼容旧字段定义。 */
        public Field(String key, String label, String format) {
            this(key, label, format, null, Map.of());
        }
    }
}
