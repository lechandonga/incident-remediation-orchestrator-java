package com.github.lechandonga.incidentremediationorchestratorjava.orchestration;

import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditEventType;
import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditLog;
import com.github.lechandonga.incidentremediationorchestratorjava.executor.ExecOutcome;
import com.github.lechandonga.incidentremediationorchestratorjava.executor.MockOpsExecutor;
import com.github.lechandonga.incidentremediationorchestratorjava.model.Remediation;
import com.github.lechandonga.incidentremediationorchestratorjava.model.RemediationStatus;
import com.github.lechandonga.incidentremediationorchestratorjava.model.StepRecord;
import com.github.lechandonga.incidentremediationorchestratorjava.model.StepResult;
import com.github.lechandonga.incidentremediationorchestratorjava.model.StepState;
import com.github.lechandonga.incidentremediationorchestratorjava.store.RemediationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 处置编排引擎。
 *
 * <p>并发控制：同一 target 上任意时刻最多一个非终态流程（{@code activeTargets}
 * CAS 占位），处置到达终态后才释放，因此“正在执行”的目标上重复提交会被明确拒绝；
 * 不同目标之间互不阻塞。
 *
 * <p>状态一致性：所有状态迁移走 {@link Remediation#transitionTo}；完成/取消/超时
 * 在工作者线程内统一做一次性终态裁决，迟到或重复的信号只写审计，不回退状态。
 */
@Service
public class RemediationOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(RemediationOrchestrator.class);

    private final RemediationStore store;
    private final MockOpsExecutor executor;
    private final AuditLog auditLog;
    private final long retryBackoffMillis;
    private final long runTimeoutMillis;

    /** target -> 占用该目标的 remediationId；占用期覆盖创建到终态释放。 */
    private final ConcurrentHashMap<String, String> activeTargets = new ConcurrentHashMap<>();
    /** remediationId -> 取消信号。 */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "remediation-worker");
        t.setDaemon(true);
        return t;
    });

    public RemediationOrchestrator(
            RemediationStore store, MockOpsExecutor executor, AuditLog auditLog,
            @Value("${orchestrator.retry-backoff-millis:50}") long retryBackoffMillis,
            @Value("${orchestrator.run-timeout-millis:30000}") long runTimeoutMillis) {
        this.store = store;
        this.executor = executor;
        this.auditLog = auditLog;
        this.retryBackoffMillis = retryBackoffMillis;
        this.runTimeoutMillis = runTimeoutMillis;
    }

    /** 尝试占用目标；仅当目标当前无在途处置时成功。 */
    public boolean tryOccupyTarget(String target, String remediationId) {
        return activeTargets.putIfAbsent(target, remediationId) == null;
    }

    public String activeRemediationOn(String target) {
        return activeTargets.get(target);
    }

    public boolean isTargetActive(String target) {
        return activeTargets.containsKey(target);
    }

    /** 异步启动处置；同一流程重复调用只会启动一次。 */
    public void start(Remediation remediation) {
        AtomicBoolean flag = new AtomicBoolean(false);
        if (cancelFlags.putIfAbsent(remediation.remediationId(), flag) != null) {
            auditLog.append(AuditEventType.ILLEGAL_TRANSITION_IGNORED,
                    remediation.remediationId(), remediation.target(), "重复启动请求被忽略");
            return;
        }
        auditLog.append(AuditEventType.REMEDIATION_CREATED,
                remediation.remediationId(), remediation.target(),
                "处置流程已创建，动作 " + remediation.action() + "，步骤数 " + remediation.steps().size());
        workers.submit(() -> run(remediation, flag));
    }

    /** 请求取消；终态下的取消请求被忽略并记录审计，不会回退状态。 */
    public boolean requestCancel(String remediationId, String reason) {
        Remediation r = store.find(remediationId).orElse(null);
        if (r == null) {
            return false;
        }
        synchronized (r) {
            if (r.status().isTerminal()) {
                auditLog.append(AuditEventType.ILLEGAL_TRANSITION_IGNORED, remediationId, r.target(),
                        "取消请求被忽略：流程已处于终态 " + r.status());
                return false;
            }
            AtomicBoolean flag = cancelFlags.get(remediationId);
            if (flag != null) {
                flag.set(true);
            }
            auditLog.append(AuditEventType.REMEDIATION_CANCEL_REQUESTED, remediationId, r.target(),
                    "收到取消请求: " + reason);
            return true;
        }
    }

    private void run(Remediation r, AtomicBoolean cancelled) {
        long deadlineNanos = System.nanoTime() + runTimeoutMillis * 1_000_000L;
        synchronized (r) {
            if (r.status() != RemediationStatus.PENDING) {
                release(r);
                return;
            }
            r.transitionTo(RemediationStatus.RUNNING, null);
            auditLog.append(AuditEventType.REMEDIATION_STARTED, r.remediationId(), r.target(),
                    "处置流程开始执行，共 " + r.steps().size() + " 步，整体超时 " + runTimeoutMillis + "ms");
        }

        RemediationStatus terminal = null;
        String terminalReason = null;

        for (StepRecord step : r.steps()) {
            synchronized (r) {
                if (cancelled.get()) {
                    markSkipped(r, step, "已取消");
                    terminal = RemediationStatus.CANCELLED;
                    terminalReason = "处置被取消";
                    break;
                }
                if (System.nanoTime() > deadlineNanos) {
                    markSkipped(r, step, "整体超时");
                    terminal = RemediationStatus.TIMED_OUT;
                    terminalReason = "处置整体超时";
                    break;
                }
                step.state(StepState.RUNNING);
                step.incrementAttempts();
                auditLog.append(AuditEventType.STEP_STARTED, r.remediationId(), r.target(),
                        "步骤开始: " + step.name() + " attempt=" + step.attempts());
            }

            String opKey = r.remediationId() + ":" + step.stepId();
            ExecOutcome outcome;
            synchronized (r) {
                outcome = invokeStep(step, opKey);
            }

            // 仅对可重试错误重试；执行器按 opKey 幂等，重试/重放不会重复生效
            while (!outcome.success()
                    && outcome.retryable()
                    && step.attempts() <= step.maxRetries()
                    && !cancelled.get()
                    && System.nanoTime() <= deadlineNanos) {
                synchronized (r) {
                    auditLog.append(AuditEventType.STEP_RETRY_SCHEDULED, r.remediationId(), r.target(),
                            "步骤将重试: " + step.name() + " nextAttempt=" + (step.attempts() + 1)
                                    + " reason=" + outcome.message());
                }
                if (!sleepBeforeRetry(cancelled, deadlineNanos)) {
                    outcome = null;
                    break;
                }
                synchronized (r) {
                    if (cancelled.get() || System.nanoTime() > deadlineNanos) {
                        outcome = null;
                        break;
                    }
                    step.incrementAttempts();
                    outcome = invokeStep(step, opKey);
                }
            }

            synchronized (r) {
                if (outcome == null) {
                    markSkipped(r, step, cancelled.get() ? "已取消" : "整体超时");
                    terminal = cancelled.get() ? RemediationStatus.CANCELLED : RemediationStatus.TIMED_OUT;
                    terminalReason = cancelled.get() ? "处置被取消" : "处置整体超时";
                    break;
                }
                step.results().add(new StepResult(step.attempts(), outcome.success(),
                        outcome.duplicate(), outcome.elapsedMillis(), outcome.message()));
                if (outcome.success()) {
                    step.state(StepState.SUCCEEDED);
                    step.lastError(null);
                    auditLog.append(AuditEventType.STEP_SUCCEEDED, r.remediationId(), r.target(),
                            "步骤成功: " + step.name()
                                    + (outcome.duplicate() ? "（幂等重放/并发去重，未重复生效）" : ""));
                } else {
                    step.state(StepState.FAILED);
                    step.lastError(outcome.message());
                    auditLog.append(AuditEventType.STEP_FAILED, r.remediationId(), r.target(),
                            "步骤失败，终止流程: " + step.name() + " attempts=" + step.attempts()
                                    + " reason=" + outcome.message());
                    terminal = RemediationStatus.FAILED;
                    terminalReason = "步骤失败: " + step.name() + " -> " + outcome.message();
                    break;
                }
            }
        }

        synchronized (r) {
            if (terminal != null) {
                for (StepRecord s : r.steps()) {
                    if (s.state() == StepState.PENDING || s.state() == StepState.RUNNING) {
                        markSkipped(r, s, terminal == RemediationStatus.CANCELLED ? "流程已取消" : "流程已终止");
                    }
                }
            }
            finishOnce(r, terminal == null ? RemediationStatus.SUCCEEDED : terminal,
                    terminal == null ? "所有步骤执行成功" : terminalReason);
        }
    }

    private ExecOutcome invokeStep(StepRecord step, String opKey) {
        return executor.execute(opKey, step.attempts(), step.action(), step.timeoutMillis());
    }

    private void markSkipped(Remediation r, StepRecord step, String why) {
        if (step.state() != StepState.SUCCEEDED) {
            step.state(StepState.SKIPPED);
            auditLog.append(AuditEventType.STEP_SKIPPED, r.remediationId(), r.target(),
                    "步骤标记跳过: " + step.name() + "（" + why + "）");
        }
    }

    private boolean sleepBeforeRetry(AtomicBoolean cancelled, long deadlineNanos) {
        long remainingMillis = Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        if (remainingMillis <= 0) {
            return false;
        }
        long waitMillis = Math.min(retryBackoffMillis, remainingMillis);
        long untilNanos = System.nanoTime() + waitMillis * 1_000_000L;
        while (System.nanoTime() < untilNanos) {
            if (cancelled.get()) {
                return false;
            }
            try {
                Thread.sleep(Math.min(5L, waitMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 终态裁决只生效一次；重复完成/超时/取消信号不会回退状态。 */
    private void finishOnce(Remediation r, RemediationStatus wanted, String reason) {
        if (r.status().isTerminal()) {
            auditLog.append(AuditEventType.ILLEGAL_TRANSITION_IGNORED, r.remediationId(), r.target(),
                    "迟到的终态信号被忽略: wanted=" + wanted + " actual=" + r.status());
            release(r);
            return;
        }
        AuditEventType eventType = switch (wanted) {
            case SUCCEEDED -> AuditEventType.REMEDIATION_SUCCEEDED;
            case FAILED -> AuditEventType.REMEDIATION_FAILED;
            case CANCELLED -> AuditEventType.REMEDIATION_CANCELLED;
            case TIMED_OUT -> AuditEventType.REMEDIATION_TIMEOUT;
            default -> throw new IllegalStateException("终态裁决收到非终态: " + wanted);
        };
        r.transitionTo(wanted, reason);
        auditLog.append(eventType, r.remediationId(), r.target(),
                "处置进入终态 " + wanted + ": " + reason);
        log.info("处置结束: id={} target={} status={}", r.remediationId(), r.target(), wanted);
        release(r);
    }

    private void release(Remediation r) {
        activeTargets.remove(r.target(), r.remediationId());
        cancelFlags.remove(r.remediationId());
    }

    /** 测试/重置用。 */
    public void reset() {
        activeTargets.clear();
        cancelFlags.clear();
    }
}
