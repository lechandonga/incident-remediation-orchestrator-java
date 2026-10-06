package com.github.lechandonga.incidentremediationorchestratorjava.model;

/** 单个步骤单次尝试的结果（记录在步骤上，便于审计与排障）。 */
public record StepResult(
        int attempt,
        boolean success,
        boolean duplicate,
        long elapsedMillis,
        String message
) {
}
