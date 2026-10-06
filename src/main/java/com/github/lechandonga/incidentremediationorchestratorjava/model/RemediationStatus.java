package com.github.lechandonga.incidentremediationorchestratorjava.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 处置流程生命周期状态。
 *
 * <pre>
 * PENDING -> RUNNING -> SUCCEEDED
 *                   |-> FAILED（步骤重试耗尽或不可重试错误）
 * RUNNING/PENDING -> CANCELLED（显式取消）
 * RUNNING -> TIMED_OUT（整体超时）
 * </pre>
 * 终态（SUCCEEDED / FAILED / CANCELLED / TIMED_OUT）不可逆，
 * 重复回放、取消、超时都不允许使状态回退或重复执行。
 */
public enum RemediationStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    /** 唯一合法的状态迁移表，迁移表之外的流转一律拒绝并记录审计。 */
    private static final Map<RemediationStatus, Set<RemediationStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(RUNNING, CANCELLED, FAILED),
            RUNNING, EnumSet.of(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT),
            SUCCEEDED, EnumSet.noneOf(RemediationStatus.class),
            FAILED, EnumSet.noneOf(RemediationStatus.class),
            CANCELLED, EnumSet.noneOf(RemediationStatus.class),
            TIMED_OUT, EnumSet.noneOf(RemediationStatus.class)
    );

    public boolean canTransitionTo(RemediationStatus next) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(RemediationStatus.class)).contains(next);
    }
}
