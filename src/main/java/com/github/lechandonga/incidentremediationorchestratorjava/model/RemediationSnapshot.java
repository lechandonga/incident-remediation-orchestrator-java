package com.github.lechandonga.incidentremediationorchestratorjava.model;

import java.time.Instant;
import java.util.List;

/** 处置流程的不可变只读视图，供 API 与审计重放使用。 */
public record RemediationSnapshot(
        String remediationId,
        String alertKey,
        String target,
        String source,
        String role,
        ActionType action,
        String idempotencyKey,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        RemediationStatus status,
        String failureReason,
        List<Remediation.StepView> steps
) {
}
