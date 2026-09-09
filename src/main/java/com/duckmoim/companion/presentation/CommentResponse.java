package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.service.WrittenComment;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 방금 쓴 댓글의 응답.
 *
 * <p><b>조회 응답과 같은 모양이 아니다.</b> API-설계.md 「2-5. 댓글 (Companion)」의 댓글 응답에는 author 블록과 availableActions
 * 가 있는데, 둘 다 요청자에 따라 달라지는 판정을 거쳐야 나온다. 도메인-모델링.md 「7.1 가시성과 권한」이 그 판정 지점을 한 곳으로 모으라고 정했고, 작성 응답에서 한
 * 번 더 조립하면 두 곳이 갈라진다. 조회 티켓이 그 한 곳을 만든다.
 *
 * <p><b>본문을 담는 것은 권한 문제가 아니다.</b> 비밀 댓글이어도 작성자 본인은 본문 열람 대상이다 (도메인-모델링.md 「7.1 가시성과 권한」). 방금 쓴 사람에게
 * 자기가 쓴 글을 돌려주는 것이라 키를 제거할 이유가 없다.
 *
 * @param createdAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 */
public record CommentResponse(
    Long id,
    Long parentId,
    boolean secret,
    CommentStatus status,
    String content,
    OffsetDateTime createdAt) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static CommentResponse from(WrittenComment written) {
    return new CommentResponse(
        written.id(),
        written.parentId(),
        written.secret(),
        written.status(),
        written.content(),
        toKst(written.createdAt()));
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
