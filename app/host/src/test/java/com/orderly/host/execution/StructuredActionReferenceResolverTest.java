package com.orderly.host.execution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredActionReferenceResolverTest {

    private final List<Map<String, Object>> outputs = List.of(Map.of(
            "entries",
            List.of(Map.of("path", "收件箱/示例.txt"))
    ));

    @Test
    void resolvesIntegerReferencesAndArrayIndexes() {
        Map<String, Object> resolved = StructuredActionReferenceResolver.resolve(
                Map.of("path", Map.of(
                        "$fromAction", 0,
                        "path", List.of("output", "entries", 0, "path")
                )),
                outputs
        );

        assertThat(resolved).containsEntry("path", "收件箱/示例.txt");
    }

    @Test
    void rejectsFractionalActionAndArrayIndexes() {
        assertThatThrownBy(() -> StructuredActionReferenceResolver.resolve(
                Map.of("path", Map.of(
                        "$fromAction", 0.5,
                        "path", List.of("output", "entries", 0, "path")
                )),
                outputs
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$fromAction 必须是整数");

        assertThatThrownBy(() -> StructuredActionReferenceResolver.resolve(
                Map.of("path", Map.of(
                        "$fromAction", 0,
                        "path", List.of("output", "entries", 0.5, "path")
                )),
                outputs
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数组索引必须是整数");
    }

    @Test
    void rejectsOverflowingIntegerIndexes() {
        assertThatThrownBy(() -> StructuredActionReferenceResolver.resolve(
                Map.of("path", Map.of(
                        "$fromAction", (long) Integer.MAX_VALUE + 1L,
                        "path", List.of("output", "entries", 0, "path")
                )),
                outputs
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$fromAction 必须是整数");
    }
}
