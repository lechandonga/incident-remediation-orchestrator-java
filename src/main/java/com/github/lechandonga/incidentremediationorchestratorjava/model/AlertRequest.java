package com.github.lechandonga.incidentremediationorchestratorjava.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 告警接入请求。必填字段缺失会被校验层拒绝（HTTP 400 + 明确原因），
 * 不会被静默吞掉。
 *
 * @param alertKey 告警指纹：同指纹 + 同目标视为“同一条告警”，用于抑制判定
 * @param target   处置目标（主机/服务标识），同目标处置串行执行
 * @param source   告警来源（如 prometheus / cloudwatch / zabbix）
 * @param role     触发者角色（admin / operator / viewer）
 * @param action   期望触发的动作
 * @param idempotencyKey 客户端幂等键，可选；相同键重复提交返回同一处置
 */
public record AlertRequest(
        @NotBlank(message = "alertKey 不能为空") String alertKey,
        @NotBlank(message = "target 不能为空") String target,
        @NotBlank(message = "source 不能为空") String source,
        @NotBlank(message = "role 不能为空") String role,
        @NotNull(message = "action 不能为空") ActionType action,
        String idempotencyKey
) {
}
