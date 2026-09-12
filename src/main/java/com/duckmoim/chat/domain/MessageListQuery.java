package com.duckmoim.chat.domain;

/**
 * 메시지 목록의 조회 조건 (CH-09).
 *
 * <p><b>거르는 조건이 방 하나다.</b> 상태로 나누지 않는다 — 지운 메시지도 자리표시자로 목록에 남아야 해서 (CH-12) 질의가 걸러 내면 그 자리가 사라진다.
 * 응답에서 본문 키를 빼는 것이 조립 쪽의 일이다.
 *
 * <p><b>요청자를 담지 않는다.</b> 알림함({@code NotificationListQuery})은 {@code recipientId} 가 질의의 유일한 방어선이라
 * 조건에 넣었지만, 여기서 요청자는 <b>「이 방을 볼 수 있는가」의 입력</b>이고 그 판정은 방 애그리게이트가 한다 ({@code ChatRoom#isMember}).
 * 질의에 섞으면 판정이 두 곳으로 갈린다.
 *
 * @param roomId 볼 방
 * @param cursor 첫 페이지면 null 이다
 * @param size 클라이언트가 준 값. 범위를 벗어나면 잘린다
 */
public record MessageListQuery(Long roomId, MessageCursor cursor, int size) {

  public static final int DEFAULT_SIZE = 30;

  public static final int MAX_SIZE = 50;

  public MessageListQuery {
    size = clampSize(size);
  }

  /**
   * 범위를 벗어난 {@code size} 는 거절하지 않고 자른다.
   *
   * <p>API-설계.md 「검증 상한」이 목록 {@code size} 를 <i>1~50 으로 조용히 맞춘다</i> 로 정했다. 51 을 보내도 400 이 아니라 50 으로
   * 돈다.
   *
   * <p><b>기본값만 다른 목록과 다르다</b> (20 → 30). 채팅 화면은 열자마자 한 화면을 채워야 하고 한 줄이 짧아 20건이면 스크롤 즉시 다음 요청이 나간다.
   * 상수를 빌려 오지 않는 것은 {@code NotificationListQuery} 와 같은 이유다 — 컨텍스트를 가로질러 참조하면 의존이 생기고, 그 의존의 대가가 상수
   * 하나보다 크다.
   */
  private static int clampSize(int size) {
    if (size < 1) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  public boolean hasCursor() {
    return cursor != null;
  }
}
