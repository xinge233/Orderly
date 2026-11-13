package com.orderly.host.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleSettingsDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path samples = Path.of("..", "..", "contracts", "samples")
            .toAbsolutePath()
            .normalize();

    @Test
    void validSettingsDeclareDefaultsAndValidateMergedValues() throws Exception {
        var manifest = objectMapper.readTree(samples.resolve("valid/module-ui-settings.json").toFile());
        ModuleSettingsDefinition definition = ModuleSettingsDefinition.parse(
                manifest.path("settings"),
                objectMapper
        );

        assertThat(definition.fields()).extracting(ModuleSettingsDefinition.Field::key)
                .containsExactly("showDetails", "pageSize", "sortBy", "note");
        assertThat(definition.defaults())
                .containsEntry("showDetails", true)
                .containsEntry("pageSize", 20)
                .containsEntry("sortBy", "name")
                .containsEntry("note", "");

        Map<String, Object> merged = definition.validateAndMerge(
                objectMapper.readTree("{\"pageSize\":50,\"sortBy\":\"modified\"}"),
                definition.defaults(),
                objectMapper
        );
        assertThat(merged)
                .containsEntry("showDetails", true)
                .containsEntry("pageSize", 50)
                .containsEntry("sortBy", "modified");
    }

    @Test
    void arraySettingsAndTitleAliasAreAccepted() throws Exception {
        var manifest = objectMapper.readTree("""
                {
                  "settings": [
                    {
                      "key": "showCompleted",
                      "title": "显示已完成",
                      "type": "boolean",
                      "default": true
                    }
                  ]
                }
                """);
        ModuleSettingsDefinition definition = ModuleSettingsDefinition.parse(
                manifest.path("settings"),
                objectMapper
        );

        assertThat(definition.defaults()).containsEntry("showCompleted", true);
    }

    @Test
    void duplicateKeysAndInvalidSelectDefaultsAreRejected() throws Exception {
        var duplicate = objectMapper.readTree(
                samples.resolve("invalid/module-ui-settings-duplicate-key.json").toFile()
        ).path("settings");
        assertThatThrownBy(() -> ModuleSettingsDefinition.parse(duplicate, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key 重复");

        var invalidDefault = objectMapper.readTree(
                samples.resolve("invalid/module-ui-settings-invalid-default.json").toFile()
        ).path("settings");
        assertThatThrownBy(() -> ModuleSettingsDefinition.parse(invalidDefault, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("默认值不在 options");
    }

    @Test
    void settingValuesRejectUnknownKeysTypesAndOptionValues() throws Exception {
        ModuleSettingsDefinition definition = ModuleSettingsDefinition.parse(
                objectMapper.readTree("""
                        {
                          "fields": [
                            {"key":"count","type":"integer","label":"数量","default":1,"min":1,"max":5},
                            {"key":"mode","type":"select","label":"模式","default":"a",
                             "options":[{"value":"a","label":"A"}]}
                          ]
                        }
                        """),
                objectMapper
        );

        assertThatThrownBy(() -> definition.validateAndMerge(
                objectMapper.readTree("{\"unknown\":true}"),
                Map.of(),
                objectMapper
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未声明字段");
        assertThatThrownBy(() -> definition.validateAndMerge(
                objectMapper.readTree("{\"count\":\"1\"}"),
                Map.of(),
                objectMapper
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("类型");
        assertThatThrownBy(() -> definition.validateAndMerge(
                objectMapper.readTree("{\"mode\":\"b\"}"),
                Map.of(),
                objectMapper
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("options");
    }
}
