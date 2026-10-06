package com.github.lechandonga.incidentremediationorchestratorjava.model;

/**
 * 接入判定结果。{@code duplicate=true} 表示本次未新建流程
 *（抑制窗口命中、目标繁忙或幂等重放），返回的 remediationId 指向既有流程。
 */
public record IngestionResult(
        IngestionOutcome outcome,
        String remediationId,
        boolean duplicate,
        String reasonDetail,
        RejectReason rejectReason
) {
    public static IngestionResult accepted(String id) {
        return new IngestionResult(IngestionOutcome.ACCEPTED, id, false, "已创建并开始执行处置流程", null);
    }

    public static IngestionResult replay(String id, String detail) {
        return new IngestionResult(IngestionOutcome.ACCEPTED, id, true, detail, null);
    }

    public static IngestionResult suppressed(String id, String detail) {
        return new IngestionResult(IngestionOutcome.SUPPRESSED_DUPLICATE, id, true, detail, null);
    }

    public static IngestionResult maintenance(String detail) {
        return new IngestionResult(IngestionOutcome.SKIPPED_MAINTENANCE, null, false, detail, null);
    }

    public static IngestionResult busy(String id, String detail) {
        return new IngestionResult(IngestionOutcome.SKIPPED_TARGET_BUSY, id, true, detail, null);
    }

    public static IngestionResult rejected(RejectReason reason, String detail) {
        return new IngestionResult(IngestionOutcome.REJECTED_POLICY, null, false, detail, reason);
    }
}
