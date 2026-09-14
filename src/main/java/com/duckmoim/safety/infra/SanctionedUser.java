package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.Sanction;

/**
 * 제재 한 건과 제재받은 회원의 이름을 함께 읽은 결과 (AD-10).
 *
 * <p>{@code Sanction} 은 대상을 {@code userId} 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). 그런데 백오피스 목록에는
 * 누가 제재 중인지가 이름으로 보여야 해서 조회 시점에 조인한다. 같은 문서가 그 경우를 이미 허용했다 — <i>"이 규칙은 쓰기 모델의 것이다. 조회 전용 쿼리는 조인해도
 * 된다."</i>
 *
 * <p><b>{@code JOIN} 이다.</b> 신고 목록의 대상과 달리 여기 짝이 없으면 데이터가 깨진 것이다 — 제재는 회원에게만 걸리고 탈퇴는 파기까지 회원 행을
 * 남긴다. 조용히 null 로 내리는 것보다 눈에 띄는 편이 낫다 ({@code ReportQueryRepositoryImpl} 이 신고자에 대해 같은 판단을 했다).
 *
 * @param nickname 제재받은 회원의 닉네임. 카카오 회원번호는 싣지 않는다
 */
public record SanctionedUser(Sanction sanction, String nickname) {}
