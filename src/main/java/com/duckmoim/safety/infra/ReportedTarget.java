package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.Report;

/**
 * 신고 한 건과 화면에 필요한 이름들을 함께 읽은 결과 (AD-02).
 *
 * <p>{@code Report} 는 신고자와 대상을 ID 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). 그런데 백오피스 목록에는 누가 누구를
 * 신고했는지가 이름으로 보여야 해서 조회 시점에 조인한다. 같은 문서가 그 경우를 이미 허용했다 — <i>"이 규칙은 쓰기 모델의 것이다. 조회 전용 쿼리는 조인해도
 * 된다."</i>
 *
 * @param subject 신고당한 쪽의 표시명. 대상 종류마다 다른 곳에서 온다 — {@code USER} 는 닉네임, {@code POST} 는 모집글 제목, {@code
 *     COMMENT} 는 <b>댓글 작성자 닉네임</b>이다 (STAR-78 에서 정했다. 댓글은 제목이 없고 본문은 목록에 실을 수 없다). <b>대상 행을 못 찾으면
 *     null 이다</b> — 그래도 신고는 목록에 남는다 (STAR-60)
 * @param secret 대상이 비밀 댓글인가. 댓글이 아니면 null 이고, 호출부가 false 로 읽는다. 이 값이 true 면 프론트가 「본문 보기」를 따로 눌러
 *     CM-17 경로를 부르고 그때 감사 로그가 남는다
 */
public record ReportedTarget(
    Report report, String reporterNickname, String subject, Boolean secret) {}
