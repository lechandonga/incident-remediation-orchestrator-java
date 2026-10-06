package com.github.lechandonga.incidentremediationorchestratorjava.ingest;

import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditEventType;
import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditLog;
import com.github.lechandonga.incidentremediationorchestratorjava.model.ActionType;
import com.github.lechandonga.incidentremediationorchestratorjava.model.AlertRequest;
import com.github.lechandonga.incidentremediationorchestratorjava.model.IngestionResult;
import com.github.lechandonga.incidentremediationorchestratorjava.model.Remediation;
import com.github.lechandonga.incidentremediationorchestratorjava.orchestration.PlaybookFactory;
import com.github.lechandonga.incidentremediationorchestratorjava.orchestration.RemediationOrchestrator;
import com.github.lechandonga.incidentremediationorchestratorjava.policy.PolicyDecision;
import com.github.lechandonga.incidentremediationorchestratorjava.policy.PolicyService;
import com.github.lechandonga.incidentremediationorchestratorjava.store.MaintenanceWindowService;
import com.github.lechandonga.incidentremediationorchestratorjava.store.RemediationStore;
import com.github.lechandonga.incidentremediationorchestratorjava.store.SuppressionTracker;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警接入编排。任何入口（REST/重放/内部调用）都走这一条判定链，
 * 保证各入口行为一致、原因可区分：
 *
 * <pre>
 * 1. 幂等键重放（同 idempotencyKey 直接返回既有处置，不重复执行）
 * 2. 维护窗口 -> SKIPPED_MAINTENANCE
 * 3. 抑制窗口（同 alertKey+target） -> SUPPRESSED_DUPLICATE
 * 4. 策略/权限 -> REJECTED_POLICY（原因细分）
 * 5. 同目标并发占用 -> SKIPPED_TARGET_BUSY
 * 6. 建单并异步执行 -> ACCEPTED
 * </pre>
 * 顺序约定：维护窗口与抑制属于“无需/不允许触发处置”的确定性事实，先于
 * 权限判定，避免维护期内的告警因权限问题报出误导性原因；而只有真正要建单时
 * 才竞争目标占用。
 */
@Service
public class AlertIngestionService {
    private final PolicyService policyService;
    private final MaintenanceWindowService maintenanceService;
    private final SuppressionTracker suppressionTracker;
    private final RemediationOrchestrator orchestrator;
    private final RemediationStore store;
    private final PlaybookFactory playbookFactory;
    private final AuditLog auditLog;
    private final Clock clock;

    /** idempotencyKey -> remediationId。 */
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();

    public AlertIngestionService(PolicyService policyService,
                                 MaintenanceWindowService maintenanceService,
                                 SuppressionTracker suppressionTracker,
                                 RemediationOrchestrator orchestrator,
                                 RemediationStore store,
                                 PlaybookFactory playbookFactory,
                                 AuditLog auditLog, Clock clock) {
        this.policyService = policyService;
        this.maintenanceService = maintenanceService;
        this.suppressionTracker = suppressionTracker;
        this.orchestrator = orchestrator;
        this.store = store;
        this.playbookFactory = playbookFactory;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public IngestionResult ingest(AlertRequest request) {
        auditLog.append(AuditEventType.ALERT_RECEIVED, null, request.target(),
                "收到告警: alertKey=" + request.alertKey()
                        + " source=" + request.source() + " role=" + request.role()
                        + " action=" + request.action()
                        + (request.idempotencyKey() != null ? " idempotencyKey=" + request.idempotencyKey() : ""));

        // 1. 幂等重放
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            String existing = idempotencyIndex.get(request.idempotencyKey());
            if (existing != null) {
                IngestionResult replay = IngestionResult.replay(existing,
                        "幂等键 " + request.idempotencyKey() + " 已存在，返回既有处置，不重复执行");
                auditLog.append(AuditEventType.IDEMPOTENT_REPLAY, existing, request.target(), replay.reasonDetail());
                return replay;
            }
        }

        // 2. 维护窗口
        if (maintenanceService.isInMaintenance(request.target())) {
            IngestionResult result = IngestionResult.maintenance(
                    "目标 " + request.target() + " 处于维护窗口，跳过处置");
            auditLog.append(AuditEventType.ALERT_SKIPPED_MAINTENANCE, null, request.target(), result.reasonDetail());
            return result;
        }

        // 3. 抑制窗口（同一条告警重复上报）
        Optional<SuppressionTracker.SuppressionEntry> suppressed =
                suppressionTracker.registerIfAbsent(request.alertKey(), request.target());
        if (suppressed.isPresent()) {
            String rid = suppressed.get().remediationId();
            long remaining = suppressed.get().remainingMillis(clock.instant(), suppressionTracker.windowMillis());
            IngestionResult result = IngestionResult.suppressed(rid,
                    "同一条告警处于抑制窗口内（剩余约 " + remaining + "ms），不重复触发处置");
            auditLog.append(AuditEventType.ALERT_SUPPRESSED, rid, request.target(), result.reasonDetail());
            return result;
        }

        // 4. 策略与权限
        PolicyDecision decision = policyService.evaluate(request.source(), request.role(), request.action());
        if (!decision.allowed()) {
            IngestionResult result = IngestionResult.rejected(decision.rejectReason(), decision.detail());
            auditLog.append(AuditEventType.POLICY_REJECTED, null, request.target(),
                    "拒绝创建处置: reason=" + decision.rejectReason() + " detail=" + decision.detail());
            // 拒绝不应长期抑制同一条告警的后续合法重试，这里移除刚刚的抑制登记
            suppressionTracker.remove(request.alertKey(), request.target());
            return result;
        }

        // 5. 同目标并发控制（已有处置正在执行）
        String activeId = orchestrator.activeRemediationOn(request.target());
        if (activeId != null) {
            IngestionResult result = IngestionResult.busy(activeId,
                    "目标 " + request.target() + " 上已有处置 " + activeId + " 正在执行，拒绝并发提交");
            auditLog.append(AuditEventType.ALERT_SKIPPED_TARGET_BUSY, activeId, request.target(), result.reasonDetail());
            suppressionTracker.remove(request.alertKey(), request.target());
            return result;
        }

        // 6. 建单：先生成 ID 并 CAS 占用目标，防止并发提交双双通过
        String remediationId = playbookFactory.newRemediationId();
        if (!orchestrator.tryOccupyTarget(request.target(), remediationId)) {
            String winner = orchestrator.activeRemediationOn(request.target());
            IngestionResult result = IngestionResult.busy(winner,
                    "并发提交竞争失败，目标 " + request.target() + " 已被处置 " + winner + " 占用");
            auditLog.append(AuditEventType.ALERT_SKIPPED_TARGET_BUSY, winner, request.target(), result.reasonDetail());
            suppressionTracker.remove(request.alertKey(), request.target());
            return result;
        }

        Remediation remediation = new Remediation(
                remediationId, request, playbookFactory.buildSteps(request.action()));
        store.put(remediation);
        // 将抑制登记与幂等键指向真正的 remediationId（此前占位为 null）
        suppressionTracker.register(request.alertKey(), request.target(), remediationId);
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            idempotencyIndex.put(request.idempotencyKey(), remediationId);
        }
        orchestrator.start(remediation);
        return IngestionResult.accepted(remediationId);
    }

    public void reset() {
        idempotencyIndex.clear();
    }
}
