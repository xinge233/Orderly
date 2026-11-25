package com.orderly.host.desktop;

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
    static final String DESKTOP_HOST_RELATIVE_PATH =
            "app/desktop/runtime/OrderlyWebView2Host.exe";
    static final String WEBVIEW2_RUNTIME_RELATIVE_PATH = "webview2-runtime";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(90);
    private static final String SHUTDOWN_ACTION_HEADER = "X-Orderly-Action";

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
                activateExistingDesktop(appHome, dataRoot);
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
            Process desktop = startDesktopHost(appHome, dataRoot, port);
            desktop.waitFor();
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
        Process[] processTree = new Process[] { process, null };
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> {
                    terminateProcessTree(processTree[1]);
                    terminateProcessTree(processTree[0]);
                },
                "orderly-launcher-shutdown"
        ));

        if (!waitForHost(process, port)) {
            terminateProcessTree(process);
            throw new IllegalStateException(
                    "本地宿主未在 90 秒内就绪。诊断日志：" + launcherLog.getParent()
            );
        }
        Process desktop = startDesktopHost(appHome, dataRoot, port);
        processTree[1] = desktop;
        int desktopExitCode = desktop.waitFor();
        if (process.isAlive()) {
            requestHostShutdown(port);
        }
        if (!process.waitFor(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            terminateProcessTree(desktop);
            terminateProcessTree(process);
            throw new IllegalStateException(
                    "桌面窗口关闭后本地服务未在 "
                            + SHUTDOWN_TIMEOUT.toSeconds()
                            + " 秒内退出。诊断日志："
                            + launcherLog.getParent()
            );
        }
        int exitCode = process.exitValue();
        if (desktopExitCode != 0) {
            throw new IllegalStateException(
                    "WebView2 桌面窗口异常退出，退出码：" + desktopExitCode
                            + "；诊断日志：" + launcherLog.getParent()
            );
        }
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

    static List<String> buildDesktopHostArguments(
            Path desktopHost,
            Path dataRoot,
            Path runtimeFolder,
            int port,
            boolean fixedRuntime
    ) {
        List<String> arguments = new ArrayList<>();
        arguments.add(desktopHost.toString());
        arguments.add("--url");
        arguments.add("http://127.0.0.1:" + port + "/");
        arguments.add("--data-root");
        arguments.add(dataRoot.toString());
        arguments.add("--mode");
        arguments.add("packaged");
        if (fixedRuntime) {
            arguments.add("--runtime-folder");
            arguments.add(runtimeFolder.toString());
        }
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

    private static Process startDesktopHost(Path appHome, Path dataRoot, int port)
            throws IOException {
        Path desktopHost = appHome.resolve(DESKTOP_HOST_RELATIVE_PATH).normalize();
        requireFile(desktopHost, "WebView2 桌面宿主");
        Path fixedRuntime = appHome.resolve(WEBVIEW2_RUNTIME_RELATIVE_PATH).normalize();
        boolean useFixedRuntime = Files.isRegularFile(
                fixedRuntime.resolve("msedgewebview2.exe")
        );
        List<String> arguments = buildDesktopHostArguments(
                desktopHost,
                dataRoot,
                fixedRuntime,
                port,
                useFixedRuntime
        );
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.directory(appHome.toFile());
        Map<String, String> environment = builder.environment();
        environment.put("ORDERLY_DATA_DIR", dataRoot.toString());
        environment.put("ORDERLY_HTTP_PORT", Integer.toString(port));
        Path logs = dataRoot.resolve("logs");
        Files.createDirectories(logs);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                logs.resolve("desktop.out.log").toFile()
        ));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(
                logs.resolve("desktop.err.log").toFile()
        ));
        return builder.start();
    }

    private static void activateExistingDesktop(Path appHome, Path dataRoot) {
        Path desktopHost = appHome.resolve(DESKTOP_HOST_RELATIVE_PATH).normalize();
        if (!Files.isRegularFile(desktopHost)) {
            showMessage("归序已在运行，但 WebView2 桌面宿主不存在。");
            return;
        }
        try {
            Process process = new ProcessBuilder(
                    desktopHost.toString(),
                    "--activate",
                    "--data-root",
                    dataRoot.toString()
            ).directory(appHome.toFile()).start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                showMessage("归序已在运行，但无法激活现有窗口。");
            }
        } catch (IOException exception) {
            showMessage("归序已在运行，但无法激活现有窗口。");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            showMessage("归序已在运行，但激活现有窗口被中断。");
        }
    }

    private static void showMessage(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            JOptionPane.showMessageDialog(
                    null,
                    message,
                    "归序 Orderly",
                    JOptionPane.INFORMATION_MESSAGE
            );
        } catch (RuntimeException ignored) {
            // 启动日志保留诊断信息。
        }
    }

    private static boolean requestHostShutdown(int port) {
        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/system/shutdown");
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header(SHUTDOWN_ACTION_HEADER, "shutdown")
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build();
            java.net.http.HttpResponse<Void> response = client.send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers.discarding()
            );
            return response.statusCode() == 200 || response.statusCode() == 202;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
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
