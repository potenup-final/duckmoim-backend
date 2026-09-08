package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CompanionPost;
import java.time.LocalDateTime;

/**
 * 모집글과 그 방장, 그리고 붙은 행사의 외부 식별자를 함께 읽은 결과.
 *
 * <p>{@code CompanionPost} 는 방장을 {@code hostId} 로, 행사를 숫자 PK 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조
 * 규칙」). 그런데 응답의 {@code author} 블록에는 닉네임과 아바타가 필요하고 {@code eventId} 는 <b>외부 식별자</b>여야 해서 (API-설계.md
 * 「2-3. 행사 (Catalog)」) 조회 시점에 조인한다. 같은 문서가 그 경우를 이미 허용했다 — <i>"이 규칙은 쓰기 모델의 것이다. 조회 전용 쿼리는 조인해도
 * 된다."</i>
 *
 * <p><b>행사명과 이미지는 여기 없다.</b> 그 둘은 모집글이 복제해 가진 스냅샷이라 조인 결과가 아니라 {@code post} 에서 나온다 (도메인-모델링.md 「3.2
 * 애그리게이트 간 참조 규칙」). 조인해서 덮어쓰면 <b>행사가 바뀌어도 모집글은 자기 값으로 그려진다</b>는 규칙이 깨진다.
 *
 * <p><b>{@code lastSeenAt} 은 저장된 값 그대로다.</b> 구간으로 줄이는 것은 현재 시각이 필요한 일이라 service 가 한다 — {@code
 * LastSeen} 이 {@code Clock} 을 받는다. {@code AuthoredComment} 와 같은 자리다.
 *
 * @param eventExternalId 행사를 안 고른 글은 {@code null} 이다. 그때는 {@code post} 의 행사명 · 이미지도 함께 {@code null}
 *     이다 (화면-계약.md 「모집글 · 댓글 (PO · CM)」)
 */
public record AuthoredPost(
    CompanionPost post,
    String nickname,
    String profileImageUrl,
    LocalDateTime lastSeenAt,
    String eventExternalId) {}
