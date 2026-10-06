package com.github.lechandonga.incidentremediationorchestratorjava.web;

import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditEvent;
import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditLog;
import com.github.lechandonga.incidentremediationorchestratorjava.audit.AuditReplayer;
import com.github.lechandonga.incidentremediationorchestratorjava.executor.AttemptMode;
import com.github.lechandonga.incidentremediationorchestratorjava.executor.FaultScript;
import com.github.lechandonga.incidentremediationorchestratorjava.executor.MockOpsExecutor;
import com.github.lechandonga.incidentremediationorchestratorjava.ingest.AlertIngestionService;
import com.github.lechandonga.incidentremediationorchestratorjava.model.AlertRequest;
import com.github.lechandonga.incidentremediationorchestratorjava.model.IngestionResult;
import com.github.lechandonga.incidentremediationorchestratorjava.model.Remediation;
import com.github.lechandonga.incidentremediationorchestratorjava.model.RemediationSnapshot;
import com.github.lechandonga.incidentremediationorchestratorjava.model.RemediationStatus;
import com.github.lechandonga.incidentremediationorchestratorjava.orchestration.RemediationOrchestrator;
import com.github.lechandonga.incidentremediationorchestratorjava.store.MaintenanceWindowService;
import com.github.lechandonga.incidentremediationorchestratorjava.store.RemediationStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 本地 REST 入口。所有入口共享同一套服务判定，行为一致、结果可解释。
 */
@RestController
@RequestMapping("/api")
public class OrchestratorController {
    private final AlertIngestionService ingestionService;
    private final RemediationOrchestrator orchestrator;
    private final RemediationStore store;
    private final MaintenanceWindowService maintenanceService;
    private final AuditLog auditLog;
    private final AuditReplayer replayer;
    private final MockOpsExecutor executor;

    public OrchestratorController(AlertIngestionService ingestionService,
                                  RemediationOrchestrator orchestrator,
                                  RemediationStore store,
                                  MaintenanceWindowService maintenanceService,
                                  AuditLog auditLog,
                                  AuditReplayer replayer,
                                  MockOpsExecutor executor) {
        this.ingestionService = ingestionService;
        this.orchestrator = orchestrator;
        this.store = store;
        this.maintenanceService = maintenanceService;
        this.auditLog = auditLog;
        this.replayer = replayer;
        this.executor = executor;
    }

    /** 告警接入（唯一接入入口）。 */
    @PostMapping("/alerts")
    public IngestionResult ingest(@Valid @RequestBody AlertRequest request) {
        return ingestionService.ingest(request);
    }

    /** 查询处置当前状态（权威状态来自聚合根）。 */
    @GetMapping("/remediations/{id}")
    public ResponseEntity<RemediationSnapshot> get(@PathVariable String id) {
        return store.find(id)
                .map(Remediation::snapshot)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** 仅凭审计事件重放出来的状态，用于与权威状态对账。 */
    @GetMapping("/remediations/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable String id) {
        Remediation remediation = store.find(id).orElse(null);
        if (remediation == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<AuditEvent> events = auditLog.byRemediation(id);
        RemediationStatus replayed = replayer.replayStatus(id, events);
        RemediationStatus actual = remediation.status();
        return ResponseEntity.ok(Map.of(
                "remediationId", id,
                "replayedStatus", replayed.name(),
                "actualStatus", actual.name(),
                "consistent", replayed == actual));
    }

    @PostMapping("/remediations/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id,
                                      @RequestParam(defaultValue = "用户取消") String reason) {
        boolean accepted = orchestrator.requestCancel(id, reason);
        return Map.of("remediationId", id, "cancelAccepted", accepted);
    }

    @GetMapping("/remediations")
    public List<RemediationSnapshot> list() {
        return store.all().stream().map(Remediation::snapshot).toList();
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit(@RequestParam(required = false) String remediationId) {
        return remediationId == null ? auditLog.all() : auditLog.byRemediation(remediationId);
    }

    /** 进入维护窗口（本地验证用）。 */
    @PostMapping("/maintenance/{target}")
    public Map<String, Object> beginMaintenance(@PathVariable String target,
                                                @RequestParam long durationMillis) {
        maintenanceService.begin(target, durationMillis);
        return Map.of("target", target, "inMaintenance", true, "durationMillis", durationMillis);
    }

    @DeleteMapping("/maintenance/{target}")
    public Map<String, Object> endMaintenance(@PathVariable String target) {
        maintenanceService.end(target);
        return Map.of("target", target, "inMaintenance", maintenanceService.isInMaintenance(target));
    }

    /** 测试支持：为某个操作键（remediationId:stepId）注入故障脚本。 */
    @PostMapping("/test/faults")
    public Map<String, Object> injectFault(@RequestBody FaultInjectionRequest request) {
        FaultScript script = new FaultScript(
                request.attemptModes() == null ? Map.of() : request.attemptModes(),
                request.latencyMillis() == null ? 5 : request.latencyMillis());
        executor.injectFault(request.operationKey(), script);
        return Map.of("operationKey", request.operationKey(), "injected", true);
    }

    public record FaultInjectionRequest(
            String operationKey,
            Map<Integer, AttemptMode> attemptModes,
            Long latencyMillis
    ) {}
}
