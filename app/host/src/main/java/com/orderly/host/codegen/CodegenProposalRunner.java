package com.orderly.host.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the system module's own Python proposal builder as a bounded child process.
 *
 * <p>The module owns prompts and output normalization. This runner only passes the
 * current text snapshot, loopback AI service address and inherited IPC token, then
 * parses the declarative result. It does not read model keys or write project files.</p>
 */
@Component
public class CodegenProposalRunner {

    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(100);

    private final ObjectMapper objectMapper;
    private final Path script;
    private final String aiServiceUrl;

    public CodegenProposalRunner(
            ObjectMapper objectMapper,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory,
            @Value("${orderly.ai-service.url:http://127.0.0.1:18080}") String aiServiceUrl
    ) {
        this.objectMapper = objectMapper;
        this.script = Path.of(moduleSourceDirectory)
                .toAbsolutePath()
                .normalize()
                .resolve("module_system_codegen")
                .resolve("ai")
                .resolve("codegen_agent.py");
        this.aiServiceUrl = aiServiceUrl;
    }

    /**
     * Executes one module-local proposal request.
     *
     * @param request declarative prompt context assembled by the host service
     * @return parsed JSON proposal
     */
    public JsonNode propose(Map<String, Object> request) {
        if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(script)) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CODEGEN_AGENT_MISSING",
                    "模块生成器 Python AI 文件缺失"
            );
        }
        List<String> command = runnerCommand();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(
                script.getParent().getParent().getParent().getParent().toFile()
        );
        builder.redirectErrorStream(false);
        Map<String, String> environment = builder.environment();
        environment.put("ORDERLY_AI_SERVICE_URL", aiServiceUrl);
        environment.put("PYTHONIOENCODING", "utf-8");
        environment.put("PYTHONUTF8", "1");

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CODEGEN_AGENT_UNAVAILABLE",
                    "无法启动模块生成器 Python AI"
            );
        }

        CompletableFuture<byte[]> stdout = readBounded(process.getInputStream());
        CompletableFuture<byte[]> stderr = readBounded(process.getErrorStream());
        try {
            byte[] input = objectMapper.writeValueAsBytes(request);
            process.getOutputStream().write(input);
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ApiException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "CODEGEN_AGENT_TIMEOUT",
                        "模块生成器 AI 超时"
                );
            }
            byte[] output = stdout.get(5, TimeUnit.SECONDS);
            byte[] errorOutput = stderr.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                String message = new String(errorOutput, StandardCharsets.UTF_8).trim();
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "CODEGEN_AGENT_FAILED",
                        message.isBlank() ? "模块生成器 AI 执行失败" : safeMessage(message)
                );
            }
            JsonNode result = objectMapper.readTree(output);
            if (result == null || !result.isObject()) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "CODEGEN_INVALID_PROPOSAL",
                        "模块生成器 AI 未返回 JSON 对象"
                );
            }
            return result;
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CODEGEN_AGENT_INTERRUPTED",
                    "模块生成器 AI 已中断"
            );
        } catch (Exception exception) {
            process.destroyForcibly();
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "CODEGEN_INVALID_PROPOSAL",
                    "模块生成器 AI 返回不可解析结果"
            );
        } finally {
            stdout.cancel(true);
            stderr.cancel(true);
        }
    }

    private List<String> runnerCommand() {
        String aiExecutable = System.getenv("ORDERLY_AI_EXECUTABLE");
        if (aiExecutable != null && !aiExecutable.isBlank()) {
            Path candidate = Path.of(aiExecutable);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return List.of(
                        candidate.toString(),
                        "--module-script",
                        script.toString()
                );
            }
        }
        String configured = System.getenv("ORDERLY_PYTHON_EXECUTABLE");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return List.of(candidate.toString(), script.toString());
            }
        }
        return List.of("python", script.toString());
    }

    private CompletableFuture<byte[]> readBounded(java.io.InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                byte[] buffer = new byte[8192];
                List<byte[]> chunks = new ArrayList<>();
                int total = 0;
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    total += read;
                    if (total > MAX_OUTPUT_BYTES) {
                        throw new IllegalStateException("生成器输出超过大小限制");
                    }
                    chunks.add(java.util.Arrays.copyOf(buffer, read));
                }
                byte[] result = new byte[total];
                int offset = 0;
                for (byte[] chunk : chunks) {
                    System.arraycopy(chunk, 0, result, offset, chunk.length);
                    offset += chunk.length;
                }
                return result;
            } catch (IOException exception) {
                throw new IllegalStateException("读取生成器输出失败", exception);
            }
        });
    }

    private String safeMessage(String message) {
        String singleLine = message.replaceAll("[\\r\\n]+", " ").trim();
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500);
    }
}
