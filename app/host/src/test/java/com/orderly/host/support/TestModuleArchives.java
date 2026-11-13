package com.orderly.host.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** 在测试临时目录生成模块 JAR，不写入真实 Module 目录。 */
public final class TestModuleArchives {

    private TestModuleArchives() {
    }

    public static void writeJar(
            Path directory,
            String fileName,
            Class<?> providerClass,
            String contractJson,
            String uiJson
    ) throws IOException {
        Files.createDirectories(directory);
        Path jar = directory.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            write(output, "META-INF/services/com.orderly.kernel.OrderlyModule",
                    providerClass.getName().getBytes(StandardCharsets.UTF_8));
            write(output, "orderly-modules/" + providerClass.getSimpleName() + ".json",
                    contractJson.getBytes(StandardCharsets.UTF_8));
            write(output, "orderly-ui/module.json", uiJson.getBytes(StandardCharsets.UTF_8));
            write(output, "orderly-ui/module.js", "export default function mount() {}\n"
                    .getBytes(StandardCharsets.UTF_8));
            write(output, "orderly-ui/module.css", ".test-module { color: var(--color-text); }\n"
                    .getBytes(StandardCharsets.UTF_8));
            String classResource = providerClass.getName().replace('.', '/') + ".class";
            try (InputStream stream = providerClass.getClassLoader().getResourceAsStream(classResource)) {
                if (stream == null) {
                    throw new IOException("找不到测试模块类资源：" + classResource);
                }
                write(output, classResource, stream.readAllBytes());
            }
        }
    }

    public static Path createSourceDirectory(Path sourceRoot, String moduleId) throws IOException {
        Path directory = sourceRoot.resolve(moduleId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        return directory;
    }

    public static String controlContract() {
        return """
                {
                  "moduleId": "module_control",
                  "title": "控制",
                  "description": "测试系统模块",
                  "order": 0,
                  "system": {"role": "MODULE_CONTROL", "protected": true},
                  "actions": [],
                  "queries": [],
                  "plannerRules": []
                }
                """;
    }

    public static String businessContract() {
        return """
                {
                  "moduleId": "module_business",
                  "title": "业务",
                  "description": "测试业务模块",
                  "order": 10,
                  "actions": [
                    {
                      "id": "business.write",
                      "title": "执行业务",
                      "description": "测试写入",
                      "risk": "WRITE",
                      "confirmationRequired": true,
                      "parameters": [
                        {
                          "name": "label",
                          "type": "string",
                          "required": false,
                          "description": "测试标签"
                        }
                      ]
                    }
                  ],
                  "queries": [
                    {
                      "id": "business.list",
                      "title": "读取业务",
                      "description": "测试查询",
                      "parameters": []
                    }
                  ],
                  "plannerRules": [
                    {
                      "id": "business.rule",
                      "actionId": "business.write",
                      "pattern": "^(执行业务)$",
                      "argumentGroups": {"label": 1},
                      "pathArguments": [],
                      "wholeGoal": true
                    }
                  ]
                }
                """;
    }

    public static String businessUi() {
        return """
                {
                  "version": 1,
                  "view": {"id": "business-test", "caption": "业务测试", "icon": "modules"},
                  "examples": [],
                  "settings": [
                    {
                      "key": "showDetails",
                      "title": "显示详情",
                      "type": "boolean",
                      "default": true
                    },
                    {
                      "key": "sortBy",
                      "title": "排序",
                      "type": "select",
                      "default": "name",
                      "options": [
                        {"value": "name", "label": "名称"},
                        {"value": "modified", "label": "修改时间"}
                      ]
                    }
                  ],
                  "script": "module.js",
                  "style": "module.css"
                }
                """;
    }

    public static String controlUi() {
        return """
                {
                  "version": 1,
                  "view": {"id": "control-test", "caption": "控制测试", "icon": "settings"},
                  "examples": [],
                  "script": "module.js",
                  "style": "module.css"
                }
                """;
    }

    public static String secondBusinessContract() {
        return """
                {
                  "moduleId": "module_business_two",
                  "title": "业务二",
                  "description": "第二个测试业务模块",
                  "order": 11,
                  "actions": [],
                  "queries": [
                    {
                      "id": "business-two.list",
                      "title": "读取业务二",
                      "description": "测试查询",
                      "parameters": []
                    }
                  ],
                  "plannerRules": []
                }
                """;
    }

    public static String secondBusinessUi() {
        return """
                {
                  "version": 1,
                  "view": {"id": "business-two-test", "caption": "业务二测试", "icon": "modules"},
                  "examples": [],
                  "script": "module.js",
                  "style": "module.css"
                }
                """;
    }

    public static String refreshContract() {
        return """
                {
                  "moduleId": "module_refresh",
                  "title": "刷新",
                  "description": "刷新追加测试模块",
                  "order": 20,
                  "actions": [],
                  "queries": [
                    {
                      "id": "refresh.list",
                      "title": "刷新查询",
                      "description": "测试刷新查询",
                      "parameters": []
                    }
                  ],
                  "plannerRules": []
                }
                """;
    }

    public static String secondSystemContract() {
        return """
                {
                  "moduleId": "module_other_control",
                  "title": "第二个控制模块",
                  "description": "刷新系统候选拒绝测试",
                  "order": 30,
                  "system": {"role": "MODULE_CONTROL", "protected": true},
                  "actions": [],
                  "queries": [],
                  "plannerRules": []
                }
                """;
    }

    public static String refreshUi() {
        return """
                {
                  "version": 1,
                  "view": {"id": "refresh-test", "caption": "刷新测试", "icon": "refresh"},
                  "examples": [],
                  "script": "module.js",
                  "style": "module.css"
                }
                """;
    }

    private static void write(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
