package com.duckmoim.chat.domain;

/**
 * 메시지의 상태 (도메인-모델링.md 「6. 라이프사이클」).
 *
 * <p>{@code ACTIVE} 에서만 출발하고 되돌아오는 전이가 없다. 작성자가 지우면 {@code DELETED} 다.
 *
 * <p><b>소프트 삭제를 별도 시각 컬럼이 아니라 이 값으로 표현한다.</b> {@code CommentStatus} 와 같은 배치이고 근거도 같다 — 지운 메시지는 사라지는
 * 것이 아니라 <b>자리표시자로 남아야</b> 해서 (CH-12) 어차피 상태가 필요하다.
 *
 * <p><b>{@code BLINDED} 가 아직 없다.</b> 신고 처리 결과로 메시지를 가리는 것은 {@code AD-09} 이고 그 티켓이 더한다 — 미리 지어내면 쓰지
 * 않는 값이 남고, 그 값이 다음 담당의 기준선이 된다. 자리가 셋이 될 것을 알면서도 둘로 두는 이유가 이것이고, 그럼에도 enum 인 것은 전이가 있기 때문이다.
 */
public enum MessageStatus {
  ACTIVE,
  DELETED
}
