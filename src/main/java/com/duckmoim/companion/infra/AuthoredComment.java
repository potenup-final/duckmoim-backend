package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.LocalDateTime;

/**
 * 댓글과 그 작성자를 함께 읽은 결과.
 *
 * <p>{@code Comment} 는 작성자를 {@code authorId} 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). 그런데 응답에는
 * 닉네임과 아바타가 필요해서 조회 시점에 조인한다. 같은 문서가 그 경우를 이미 허용했다 — <i>"이 규칙은 쓰기 모델의 것이다. 조회 전용 쿼리는 조인해도 된다."</i>
 *
 * <p><b>{@code lastSeenAt} 은 저장된 값 그대로다.</b> 구간으로 줄이는 것은 현재 시각이 필요한 일이라 service 가 한다 — {@code
 * LastSeen} 이 {@code Clock} 을 받는다.
 *
 * @param authorStatus 익명화 판정의 입력이다 (AU-11). 조인해 온 값을 그대로 싣고, 자리표시자로 바꾸는 것은 {@code AuthorDisplay} 가
 *     한다. <b>{@code nickname} 이 {@code null} 인지로 대신하지 않는 이유가 거기 적혀 있다</b>
 */
public record AuthoredComment(
    Comment comment,
    String nickname,
    String profileImageUrl,
    LocalDateTime lastSeenAt,
    SignupStatus authorStatus) {}
