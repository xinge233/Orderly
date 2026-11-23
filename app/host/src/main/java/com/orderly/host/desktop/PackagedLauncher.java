package com.orderly.host.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;

/**
 * Windows 安装版的桌面启动器。
 *
 * <p>安装版由 jpackage 启动本类。它定位安装目录和用户数据目录，再调用内置
 * {@code scripts/start.ps1} 启动 AI、Java 宿主和模块监督器。脚本仍是编排层，
 * 但 JDK、Maven、AI 可执行文件都来自应用目录，用户不需要准备外部运行时。</p>
 *
 * <p>本类不接触模型 API Key、文件内容或业务状态。临时 IPC Token 仍由
 * start.ps1 在子进程环境内生成，不进入命令行、日志或用户配置。</p>
 */
public final class PackagedLauncher {

    static final String APP_HOME_PROPERTY = "orderly.app.home";
    static final String HTTP_PORT_ENV = "ORDERLY_HTTP_PORT";
    static final int DEFAULT_HTTP_PORT = 8080;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);

    private PackagedLauncher() {
    }

    /** 启动完整安装版，并在宿主退出后返回。 */
    public static void main(String[] args) {
        try {
            launch();
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            showFailure(exception);
            System.exit(1);
        }
    }

    private static void launch() throws Exception {
        Path appHome = resolveAppHome();
        Path dataRoot = resolveDataRoot();
        Path logs = dataRoot.resolve("logs");
        Files.createDirectories(logs);
        Path launcherLog = logs.resolve("launcher.log");

        try (FileChannel lockChannel = FileChannel.open(
                dataRoot.resolve("launcher.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        )) {
            FileLock lock = lockChannel.tryLock();
            if (lock == null) {
                openBrowser(httpPort());
                return;
            }
            try {
                run(appHome, dataRoot, launcherLog);
            } finally {
                lock.release();
            }
        }
    }

    private static void showFailure(Exception exception) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = exception.getClass().getSimpleName();
        }
        try {
            JOptionPane.showMessageDialog(
                    null,
                    "归序未能启动。\n\n" + detail,
                    "归序 Orderly",
                    JOptionPane.ERROR_MESSAGE
            );
        } catch (RuntimeException ignored) {
            // stderr 和启动日志已经保留诊断信息。
        }
    }

    private static void run(Path appHome, Path dataRoot, Path launcherLog) throws Exception {
        int port = httpPort();
        if (isHostHealthy(port)) {
            openBrowser(port);
            return;
        }

        Path javaHome = appHome.resolve("toolchains").resolve("jdk");
        Path mavenHome = appHome.resolve("toolchains").resolve("maven");
        Path mavenRepository = appHome.resolve("maven-repository");
        Path aiExecutable = appHome.resolve("ai").resolve("orderly-ai.exe");
        Path startScript = appHome.resolve("scripts").resolve("start.ps1");
        Path powerShell = Path.of(
                System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe"
        );

        requireFile(powerShell, "Windows PowerShell");
        requireFile(startScript, "start.ps1");
        requireDirectory(javaHome, "内置 JDK");
        requireDirectory(mavenHome, "内置 Maven");
        requireDirectory(mavenRepository, "内置 Maven 仓库");
        requireFile(aiExecutable, "AI 可执行文件");

        ProcessBuilder builder = new ProcessBuilder(buildStartArguments(
                powerShell,
                startScript,
                appHome,
                aiExecutable,
                mavenRepository,
                port
        ));
        builder.directory(appHome.toFile());
        Map<String, String> environment = builder.environment();
        environment.put("JAVA_HOME", javaHome.toString());
        environment.put("ORDERLY_DATA_DIR", dataRoot.toString());
        environment.put("ORDERLY_HTTP_PORT", Integer.toString(port));
        putPath(
                environment,
                javaHome.resolve("bin"),
                mavenHome.resolve("bin")
        );
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                launcherLog.resolveSibling("startup.out.log").toFile()
        ));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(
                launcherLog.resolveSibling("startup.err.log").toFile()
        ));

        Process process = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> terminateProcessTree(process),
                "orderly-launcher-shutdown"
        ));

        if (!waitForHost(process, port)) {
            terminateProcessTree(process);
            throw new IllegalStateException(
                    "本地宿主未在 90 秒内就绪。诊断日志：" + launcherLog.getParent()
            );
        }
        openBrowser(port);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "归序启动脚本退出码：" + exitCode + "；诊断日志：" + launcherLog.getParent()
            );
        }
    }

    static List<String> buildStartArguments(
            Path powerShell,
            Path startScript,
            Path appHome,
            Path aiExecutable,
            Path mavenRepository,
            int port
    ) {
        List<String> arguments = new ArrayList<>();
        arguments.add(powerShell.toString());
        arguments.add("-NoProfile");
        arguments.add("-NonInteractive");
        arguments.add("-WindowStyle");
        arguments.add("Hidden");
        arguments.add("-ExecutionPolicy");
        arguments.add("Bypass");
        arguments.add("-File");
        arguments.add(startScript.toString());
        arguments.add("-SkipBuild");
        arguments.add("-BackgroundModules");
        arguments.add("-Offline");
        arguments.add("-AiExecutable");
        arguments.add(aiExecutable.toString());
        arguments.add("-MavenRepository");
        arguments.add(mavenRepository.toString());
        arguments.add("-Port");
        arguments.add(Integer.toString(port));
        return List.copyOf(arguments);
    }

    static Path resolveAppHome() throws IOException {
        String configured = System.getProperty(APP_HOME_PROPERTY, "").trim();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String jpackagePath = System.getProperty("jpackage.app-path", "").trim();
        if (!jpackagePath.isEmpty()) {
            return Path.of(jpackagePath).toAbsolutePath().getParent().normalize();
        }
        Path command = ProcessHandle.current().info().command()
                .map(Path::of)
                .orElse(null);
        if (command != null) {
            Path bin = command.getParent();
            Path runtime = bin == null ? null : bin.getParent();
            if (bin != null && runtime != null
                    && "bin".equals(bin.getFileName().toString())
                    && "runtime".equals(runtime.getFileName().toString())) {
                return runtime.getParent().toAbsolutePath().normalize();
            }
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    static Path resolveDataRoot() {
        String configured = System.getenv().getOrDefault("ORDERLY_DATA_DIR", "").trim();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv().getOrDefault("LOCALAPPDATA", "").trim();
        if (!localAppData.isEmpty()) {
            return Path.of(localAppData, "Orderly", "data").toAbsolutePath().normalize();
        }
        return Path.of(
                System.getProperty("user.home"),
                ".orderly-data"
        ).toAbsolutePath().normalize();
    }

    static int httpPort() {
        String configured = System.getenv().getOrDefault(HTTP_PORT_ENV, "").trim();
        if (configured.isEmpty()) {
            return DEFAULT_HTTP_PORT;
        }
        int port = Integer.parseInt(configured);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(HTTP_PORT_ENV + " 必须是 1-65535 的端口");
        }
        return port;
    }

    private static boolean waitForHost(Process process, int port) throws InterruptedException {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            if (isHostHealthy(port)) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    private static boolean isHostHealthy(int port) {
        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/health/live");
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            return response.statusCode() == 200 && response.body().contains("\"UP\"");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static void openBrowser(int port) {
        URI uri = URI.create("http://127.0.0.1:" + port + "/");
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
                return;
            }
        } catch (IOException | UnsupportedOperationException exception) {
            // 回退到 Windows 注册的 URL 处理器。
        }
        try {
            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", uri.toString()).start();
        } catch (IOException ignored) {
            // 启动日志已经可诊断；浏览器打开失败不终止本地服务。
        }
    }

    private static void terminateProcessTree(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            new ProcessBuilder(
                    "taskkill.exe",
                    "/PID",
                    Long.toString(process.pid()),
                    "/T",
                    "/F"
            ).start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException exception) {
            process.destroy();
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String prependPath(String current, Path... directories) {
        StringBuilder value = new StringBuilder();
        for (Path directory : directories) {
            if (!value.isEmpty()) {
                value.append(';');
            }
            value.append(directory);
        }
        if (current != null && !current.isBlank()) {
            value.append(';').append(current);
        }
        return value.toString();
    }

    static void putPath(Map<String, String> environment, Path... directories) {
        String pathKey = environment.keySet().stream()
                .filter(name -> "PATH".equalsIgnoreCase(name))
                .findFirst()
                .orElse("PATH");
        environment.put(pathKey, prependPath(environment.get(pathKey), directories));
    }

    private static void requireDirectory(Path path, String description) throws IOException {
        if (!Files.isDirectory(path)) {
            throw new IOException(description + "不存在：" + path);
        }
    }

    private static void requireFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(description + "不存在：" + path);
        }
    }
}
