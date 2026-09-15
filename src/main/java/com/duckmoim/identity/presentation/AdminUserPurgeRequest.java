package com.duckmoim.identity.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 계정 파기 요청 (AD-05).
 *
 * <p><b>사유가 필수다.</b> 되돌릴 수 없는 조치이고, 이 문장이 그대로 감사 로그의 {@code detail} 이 되어 <b>나중에 왜 파기했는지를 읽을 수 있는
 * 유일한 자리</b>가 된다. 제재가 사유를 받는 것과 같은 판단이다 — 다만 이쪽은 본인에게 보이지 않는다. 파기된 계정은 로그인할 수 없다.
 *
 * <p><b>500 자는 {@code audit_log.detail} 의 길이다</b> (V36). 여기서 막지 않으면 저장 시점에 잘리거나 터지는데, 둘 다 장부가 고칠 수
 * 없는 기록이라 늦다.
 */
public record AdminUserPurgeRequest(
    @Schema(description = "무엇 때문에 파기하는지. 감사 로그에 그대로 남는다", example = "나이 확인 요청에 30일간 답이 없었습니다")
        @NotBlank
        @Size(max = 500)
        String reason) {}
