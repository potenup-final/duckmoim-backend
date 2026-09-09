package com.duckmoim.admin.infra;

import com.duckmoim.admin.domain.AuditLog;

/**
 * 감사 로그 한 건과 그 행위자의 닉네임을 함께 읽은 결과 (AD-05).
 *
 * <p>{@code AuditLog} 는 행위자를 {@code actorUserId} 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). 그런데
 * 화면-계약.md 의 응답 {@code actor} 는 사람이 읽는 이름이라 조회 시점에 조인한다 — 같은 문서가 그 경우를 이미 허용했다: <i>"이 규칙은 쓰기 모델의
 * 것이다. 조회 전용 쿼리는 조인해도 된다."</i>
 *
 * <p>{@code AuthoredComment}(companion → identity)가 같은 자리의 선례다.
 *
 * <p><b>닉네임을 기록에 박아 두지 않는 이유.</b> 감사 로그는 고칠 수 없어서 (I-13), 박아 두면 닉네임이 바뀐 뒤에도 옛 이름이 영영 남고 그 줄만으로는
 * 누구인지 되짚을 수 없다. 신원은 회원번호가 지고 표시값은 조회 때 만든다.
 */
public record ActedAuditLog(AuditLog auditLog, String actorNickname) {}
