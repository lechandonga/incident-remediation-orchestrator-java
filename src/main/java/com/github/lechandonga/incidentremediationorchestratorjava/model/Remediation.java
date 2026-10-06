package com.github.lechandonga.incidentremediationorchestratorjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 处置流程聚合根。自身带锁：所有状态读写必须在 {@code synchronized(remediation)}
 * 块内完成，保证状态迁移、步骤推进与审计追加的原子一致。
 */
public class Remediation {
    private final String remediationId;
    private final String alertKey;
    private final String target;
    private final String source;
    private final String role;
    private final ActionType action;
    private final String idempotencyKey;
    private final Instant createdAt = Instant.now();
    private final List<StepRecord> steps = new ArrayList<>();

    private RemediationStatus status = RemediationStatus.PENDING;
    private Instant startedAt;
    private Instant finishedAt;
    private String failureReason;

    public Remediation(String remediationId, AlertRequest req, List<StepRecord> steps) {
        this.remediationId = remediationId;
        this.alertKey = req.alertKey();
        this.target = req.target();
        this.source = req.source();
        this.role = req.role();
        this.action = req.action();
        this.idempotencyKey = req.idempotencyKey();
        this.steps.addAll(steps);
    }

    public String remediationId() { return remediationId; }
    public String alertKey() { return alertKey; }
    public String target() { return target; }
    public String source() { return source; }
    public String role() { return role; }
    public ActionType action() { return action; }
    public String idempotencyKey() { return idempotencyKey; }
    public Instant createdAt() { return createdAt; }
    public List<StepRecord> steps() { return steps; }
    public RemediationStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public String failureReason() { return failureReason; }

    /**
     * 唯一的状态迁移入口。非法/回退迁移抛出异常，防止超时、取消、
     * 并发提交或重复回放导致状态回退。
     */
    public void transitionTo(RemediationStatus next, String reason) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "非法状态迁移: " + remediationId + " " + status + " -> " + next);
        }
        this.status = next;
        if (next == RemediationStatus.RUNNING && startedAt == null) {
            this.startedAt = Instant.now();
        }
        if (next.isTerminal()) {
            this.finishedAt = Instant.now();
            this.failureReason = reason;
        }
    }

    public RemediationSnapshot snapshot() {
        return new RemediationSnapshot(
                remediationId, alertKey, target, source, role, action, idempotencyKey,
                createdAt, startedAt, finishedAt, status, failureReason,
                steps.stream().map(Remediation::stepView).toList()
        );
    }

    private static StepView stepView(StepRecord s) {
        return new StepView(s.stepId(), s.name(), s.action(), s.maxRetries(), s.timeoutMillis(),
                s.state(), s.attempts(), List.copyOf(s.results()), s.lastError());
    }

    public record StepView(
            String stepId, String name, ActionType action, int maxRetries, long timeoutMillis,
            StepState state, int attempts, List<StepResult> results, String lastError
    ) {}
}
