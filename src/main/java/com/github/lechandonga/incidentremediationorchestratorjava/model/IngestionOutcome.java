package com.github.lechandonga.incidentremediationorchestratorjava.model;

/**
 * 告警接入后的判定结果。每种结果都有明确的、可区分的含义，
 * 调用方与审计记录均不得出现“静默吞掉”的情况。
 */
public enum IngestionOutcome {
    /** 通过所有校验与抑制判定，已创建并开始执行处置流程。 */
    ACCEPTED,
    /** 命中抑制窗口：同一条告警在窗口内重复上报。 */
    SUPPRESSED_DUPLICATE,
    /** 目标当前处于维护窗口，跳过处置。 */
    SKIPPED_MAINTENANCE,
    /** 同一目标上已有处置流程正在执行，跳过本次提交。 */
    SKIPPED_TARGET_BUSY,
    /** 来源不在允许范围内或角色越权，拒绝创建处置。 */
    REJECTED_POLICY
}
