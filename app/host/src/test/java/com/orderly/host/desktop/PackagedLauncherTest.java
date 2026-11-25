package com.orderly.host.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PackagedLauncherTest {

    @Test
    void resolvesConfiguredApplicationHome() throws Exception {
        String previous = System.getProperty(PackagedLauncher.APP_HOME_PROPERTY);
        Path expected = Path.of("D:", "Orderly", "installed").toAbsolutePath().normalize();
        try {
            System.setProperty(PackagedLauncher.APP_HOME_PROPERTY, expected.toString());
            assertThat(PackagedLauncher.resolveAppHome()).isEqualTo(expected);
        } finally {
            if (previous == null) {
                System.clearProperty(PackagedLauncher.APP_HOME_PROPERTY);
            } else {
                System.setProperty(PackagedLauncher.APP_HOME_PROPERTY, previous);
            }
        }
    }

    @Test
    void startupUsesBundledAiJdkMavenAndRepository() {
        Path appHome = Path.of("D:", "Orderly", "installed");
        List<String> arguments = PackagedLauncher.buildStartArguments(
                Path.of("C:", "Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
                appHome.resolve("scripts").resolve("start.ps1"),
                appHome,
                appHome.resolve("ai").resolve("orderly-ai.exe"),
                appHome.resolve("maven-repository"),
                18080
        );

        assertThat(arguments).containsSubsequence(
                "-SkipBuild",
                "-BackgroundModules",
                "-Offline",
                "-AiExecutable",
                appHome.resolve("ai").resolve("orderly-ai.exe").toString(),
                "-Port",
                "18080"
        );
        assertThat(arguments).contains(
                appHome.resolve("maven-repository").toString()
        );
    }

    @Test
    void reusesExistingPathKeyWithoutCreatingDuplicate() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("Path", "C:\\Windows\\System32");

        PackagedLauncher.putPath(
                environment,
                Path.of("D:", "Orderly", "toolchains", "jdk", "bin"),
                Path.of("D:", "Orderly", "toolchains", "maven", "bin")
        );

        assertThat(environment.keySet())
                .containsExactly("Path");
        assertThat(environment.get("Path"))
                .startsWith("D:\\Orderly\\toolchains\\jdk\\bin;D:\\Orderly\\toolchains\\maven\\bin;")
                .endsWith("C:\\Windows\\System32");
    }

    @Test
    void desktopHostReceivesOnlyLoopbackUrlDataRootAndRuntimeMode() {
        Path appHome = Path.of("D:", "Orderly", "installed");
        Path dataRoot = Path.of("C:", "Users", "tester", "AppData", "Local", "Orderly", "data");
        List<String> arguments = PackagedLauncher.buildDesktopHostArguments(
                appHome.resolve("app").resolve("desktop").resolve("runtime")
                        .resolve("OrderlyWebView2Host.exe"),
                dataRoot,
                appHome.resolve("webview2-runtime"),
                18080,
                true
        );

        assertThat(arguments).containsSubsequence(
                "--url",
                "http://127.0.0.1:18080/",
                "--data-root",
                dataRoot.toString(),
                "--mode",
                "packaged",
                "--runtime-folder",
                appHome.resolve("webview2-runtime").toString()
        );
        assertThat(arguments).noneMatch(argument -> argument.contains("API_KEY"));
    }
}
