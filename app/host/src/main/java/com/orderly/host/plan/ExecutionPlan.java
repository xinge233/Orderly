package com.orderly.host.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 旧版计划在宿主进程内的聚合状态。
 *
 * <p>实例创建时固定为 {@link PlanStatus#PREVIEW}，并只由计划执行服务在持有模块治理
 * 执行许可时推进到运行、完成或回滚状态。本类不校验迁移是否合法，也不持久化计划对象；
 * 宿主重启后只能依赖已持久化的执行记录查询历史。</p>
 */
public final class ExecutionPlan {

    private final String id;
    private final String goal;
    private final String kind;
    private final String message;
    private final List<String> assumptions;
    private final String impact;
    private final double confidence;
    private final List<ExecutionStep> steps;
    private final Instant createdAt;
    private PlanStatus status;
    private Instant completedAt;

    /**
     * 创建一份可审查的计划快照。
     *
     * <p>创建过程只复制假设与步骤列表，不复制步骤对象本身，也不产生文件或模块状态
     * 副作用。新计划固定从 {@code PREVIEW} 开始；调用方负责保证身份、元数据和步骤
     * 已完成宿主校验。</p>
     */
    public ExecutionPlan(
            String id,
            String goal,
            String kind,
            String message,
            List<String> assumptions,
            String impact,
            double confidence,
            List<ExecutionStep> steps,
            Instant createdAt
    ) {
        this.id = id;
        this.goal = goal;
        this.kind = kind;
        this.message = message;
        this.assumptions = List.copyOf(assumptions);
        this.impact = impact;
        this.confidence = confidence;
        this.steps = new ArrayList<>(steps);
        this.createdAt = createdAt;
        this.status = PlanStatus.PREVIEW;
    }

    public String id() {
        return id;
    }

    public String goal() {
        return goal;
    }

    public String kind() {
        return kind;
    }

    public String message() {
        return message;
    }

    public List<String> assumptions() {
        return assumptions;
    }

    public String impact() {
        return impact;
    }

    public double confidence() {
        return confidence;
    }

    /** 返回宿主持有的可变步骤列表；执行服务通过它记录单步状态和审计输出。 */
    public List<ExecutionStep> steps() {
        return steps;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public PlanStatus status() {
        return status;
    }

    /**
     * 更新聚合状态，并为终止态记录完成时间。
     *
     * <p>本方法只改变内存状态，不检查调用者、当前状态或执行许可。调用约定要求只有
     * 计划执行服务在合法迁移中调用它。进入 {@code COMPLETED}、{@code REJECTED}、
     * {@code ROLLED_BACK} 或 {@code PARTIALLY_ROLLED_BACK} 时写入当前时间；重新进入
     * 非终止态不会清空既有完成时间。</p>
     */
    public void status(PlanStatus value) {
        this.status = value;
        if (value == PlanStatus.COMPLETED
                || value == PlanStatus.REJECTED
                || value == PlanStatus.ROLLED_BACK
                || value == PlanStatus.PARTIALLY_ROLLED_BACK) {
            this.completedAt = Instant.now();
        }
    }

    public Instant completedAt() {
        return completedAt;
    }
}
