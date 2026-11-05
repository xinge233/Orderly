package com.orderly.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.platform.module.ModuleRegistry;
import com.orderly.platform.module.SampleTestModule;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定公共契约序列化测试。
 *
 * <p>任何内部模型变化如果意外改变公开字段、动作 schema 或安全语义，
 * 都会与固定样例产生差异并使测试失败。</p>
 */
class ModuleCatalogContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMatchTheVersionedPublicContractFixture() throws Exception {
        ModuleRegistry registry = new ModuleRegistry(List.of(new SampleTestModule()));
        ModuleCatalogMapper mapper = new ModuleCatalogMapper(registry);

        JsonNode actual = objectMapper.valueToTree(mapper.toResponse());
        JsonNode expected;
        try (InputStream fixture = getClass().getResourceAsStream(
                "/module-catalog.sample.json"
        )) {
            assertThat(fixture).as("固定契约样例必须存在于测试资源中").isNotNull();
            expected = objectMapper.readTree(fixture);
        }

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.toString()).doesNotContain("capabilities");
    }
}
