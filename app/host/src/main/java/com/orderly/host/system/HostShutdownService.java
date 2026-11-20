package com.orderly.host.system;

import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 受控宿主退出服务。
 *
 * <p>退出请求先通过模块协调器检查执行状态并冻结新的执行许可，再异步关闭 Spring
 * 上下文和 Java 进程。HTTP 响应必须先返回，随后由启动脚本负责清理 AI 服务和后台
 * 模块构建器。该服务只负责本地宿主生命周期，不接触模块业务能力。</p>
 */
@Service
public class HostShutdownService {

    static final long EXIT_DELAY_MILLIS = 350L;

    private final ApplicationContext applicationContext;
    private final ModuleControlCoordinator coordinator;
    private final ExecutionJournal journal;
    private final ScheduledExecutorService scheduler;
    private final boolean enabled;

    @Autowired
    public HostShutdownService(
            ApplicationContext applicationContext,
            ModuleControlCoordinator coordinator,
            ExecutionJournal journal,
            @Value("${orderly.shutdown-enabled:true}") boolean enabled
    ) {
        this(
                applicationContext,
                coordinator,
                journal,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "orderly-host-shutdown");
                    thread.setDaemon(true);
                    return thread;
                }),
                enabled
        );
    }

    HostShutdownService(
            ApplicationContext applicationContext,
            ModuleControlCoordinator coordinator,
            ExecutionJournal journal,
            ScheduledExecutorService scheduler,
            boolean enabled
    ) {
        this.applicationContext = applicationContext;
        this.coordinator = coordinator;
        this.journal = journal;
        this.scheduler = scheduler;
        this.enabled = enabled;
    }

    /**
     * 请求关闭宿主；重复请求保持幂等，已有执行或禁用时返回明确冲突。
     */
    public ShutdownAccepted requestShutdown() {
        if (!enabled) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "HOST_SHUTDOWN_DISABLED",
                    "当前宿主配置不允许通过界面退出"
            );
        }
        if (!coordinator.beginShutdown()) {
            return accepted(false);
        }
        try {
            journal.append(
                    "HOST_LIFECYCLE",
                    "SYSTEM",
                    null,
                    null,
                    null,
                    "shutdown",
                    "STOPPING",
                    "用户请求退出归序；正在停止本地宿主"
            );
            scheduler.schedule(this::exitProcess, EXIT_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            return accepted(true);
        } catch (RuntimeException exception) {
            coordinator.cancelShutdown();
            throw exception;
        }
    }

    private ShutdownAccepted accepted(boolean newlyRequested) {
        return new ShutdownAccepted(
                "STOPPING",
                newlyRequested ? "正在停止本地宿主与相关进程" : "宿主已在退出流程中"
        );
    }

    private void exitProcess() {
        int exitCode = SpringApplication.exit(applicationContext, () -> 0);
        System.exit(exitCode);
    }

    /** 退出接口的稳定响应；只声明状态和用户可读说明。 */
    public record ShutdownAccepted(String status, String message) {
    }
}
