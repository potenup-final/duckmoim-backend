package com.duckmoim.notification.domain;

/**
 * 알림함의 조회 조건 (NT-08).
 *
 * <p><b>거르는 조건이 수신자 하나다.</b> 읽음 여부로 나누지 않는다 — 알림함은 읽은 것도 함께 보이고, 안 읽은 수는 따로 세는 값이다 (NT-10).
 *
 * <p><b>{@code recipientId} 가 이 레코드에서 가장 중요한 필드다.</b> {@code I-24}(알림은 수신자 본인에게만 조회된다) 는 이중 방어가
 * 없어서, 질의에 이 조건이 걸리는 것이 <b>유일한 방어선</b>이다 (도메인-모델링.md 「5. 불변식」). 그래서 개별 알림을 가리키는 경로 자체를 두지 않았고
 * (API-설계.md 「5. 결정 사항」 D-14), 목록이 곧 본인 것뿐이라는 성질을 이 타입이 들고 있다.
 *
 * @param recipientId 요청자 자신. <b>요청 본문이나 파라미터로 받지 않는다</b> — 인증 주체에서 온다. 남의 알림함을 보는 경로가 생기면 안 된다
 * @param cursor 첫 페이지면 null 이다
 * @param size 클라이언트가 준 값. 범위를 벗어나면 잘린다
 */
public record NotificationListQuery(Long recipientId, NotificationCursor cursor, int size) {

  public static final int DEFAULT_SIZE = 20;

  public static final int MAX_SIZE = 50;

  public NotificationListQuery {
    size = clampSize(size);
  }

  /**
   * 범위를 벗어난 {@code size} 는 거절하지 않고 자른다.
   *
   * <p>API-설계.md 「검증 상한」이 목록 {@code size} 를 <i>1~50 으로 조용히 맞춘다</i> 로 정했다. 51 을 보내도 400 이 아니라 50 으로
   * 돈다. 다른 목록들과 같은 값이지만 상수를 빌려 오지 않는다 — 컨텍스트를 가로질러 참조하면 의존이 생기고, 그 의존의 대가가 상수 하나보다 크다.
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
