package com.orderly.host.codegen;

import com.orderly.host.api.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Starts isolated loopback host instances for generated candidates.
 *
 * <p>A preview gets its own port and data directory, a copied module directory with
 * the candidate artifact substituted, and an optional copied theme directory. The
 * running application is never restarted or hot-replaced.</p>
 */
@Component
public class CodegenPreviewService {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);

    private final String aiServiceUrl;
    private final Path moduleSourceDirectory;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final HttpClient healthClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public CodegenPreviewService(
            @Value("${orderly.ai-service.url:http://127.0.0.1:18080}") String aiServiceUrl,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory
    ) {
        this.aiServiceUrl = aiServiceUrl;
        this.moduleSourceDirectory = Path.of(moduleSourceDirectory)
                .toAbsolutePath()
                .normalize();
    }

    /** Start one candidate host and return its loopback URL. */
    public synchronized PreviewHandle start(
            String sessionId,
            Path modulesDirectory,
            Path themesDirectory,
            Path previewDataDirectory
    ) {
        stop(sessionId);
        Path hostJar = currentHostJar();
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        if (!Files.isRegularFile(javaExecutable, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CODEGEN_PREVIEW_JAVA_MISSING",
                    "无法定位 Java 运行时以启动候选预览"
            );
        }
        int port = freePort();
        try {
            Files.createDirectories(previewDataDirectory);
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "CODEGEN_PREVIEW_DATA_FAILED",
                    "无法创建候选预览数据目录"
            );
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dorderly.modules-dir=" + modulesDirectory.toAbsolutePath());
        command.add("-Dorderly.themes-dir=" + themesDirectory.toAbsolutePath());
        command.add("-Dorderly.module-source-dir=" + moduleSourceDirectory);
        command.add("-Dorderly.data-dir=" + previewDataDirectory.toAbsolutePath());
        command.add("-Dorderly.ai-service.url=" + aiServiceUrl);
        command.add("-Dorderly.background-modules-enabled=false");
        command.add("-jar");
        command.add(hostJar.toString());
        command.add("--server.port=" + port);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(hostJar.getParent().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                previewDataDirectory.resolve("preview-host.log").toFile()
        ));
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CODEGEN_PREVIEW_START_FAILED",
                    "无法启动候选镜像宿主"
            );
        }
        processes.put(sessionId, process);
        String url = "http://127.0.0.1:" + port + "/";
        if (!waitUntilHealthy(process, url)) {
            stop(sessionId);
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "CODEGEN_PREVIEW_NOT_READY",
                    "候选镜像宿主未在时限内就绪"
            );
        }
        return new PreviewHandle(url, port, process.pid());
    }

    /** Stop a preview instance and its child process tree best-effort. */
    public synchronized void stop(String sessionId) {
        Process process = processes.remove(sessionId);
        if (process == null) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Stop all candidate hosts owned by this application process. */
    @PreDestroy
    public void stopAll() {
        List.copyOf(processes.keySet()).forEach(this::stop);
    }

    private boolean waitUntilHealthy(Process process, String url) {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "api/health/live"))
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            try {
                HttpResponse<Void> response = healthClient.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );
                if (response.statusCode() == 200) {
                    return true;
                }
            } catch (IOException ignored) {
                // The mirror host is still starting.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Path currentHostJar() {
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && candidate.getFileName().toString().endsWith(".jar")) {
                return candidate;
            }
        }
        try {
            Path location = Path.of(
                    getClass().getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
            if (!Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS)
                    || !location.getFileName().toString().endsWith(".jar")) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "CODEGEN_PREVIEW_HOST_UNAVAILABLE",
                        "当前为开发类目录运行，无法启动镜像宿主；请使用构建后的宿主 JAR"
                );
            }
            return location;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CODEGEN_PREVIEW_HOST_UNAVAILABLE",
                    "无法定位当前宿主 JAR"
            );
        }
    }

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CODEGEN_PREVIEW_PORT_FAILED",
                    "无法分配候选预览端口"
            );
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    /** Immutable preview endpoint details. */
    public record PreviewHandle(String url, int port, long processId) {
    }
}
