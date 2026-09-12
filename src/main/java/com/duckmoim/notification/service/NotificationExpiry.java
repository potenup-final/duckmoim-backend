package com.duckmoim.notification.service;

/**
 * 청크 하나의 만료 처리 결과 (NT-11a).
 *
 * <p><b>둘을 따로 세는 이유는 배치가 둘이 돌기 때문이다.</b> 집어 둔 행을 다른 인스턴스가 먼저 지우면 {@code deleted} 가 {@code picked}
 * 보다 적게 나온다 (ADR 0009). 그 차이가 실제로 생기는 값이라 하나로 합치면 둘 중 하나가 거짓이 된다.
 *
 * @param picked 만료 조건에 걸려 집은 건수. <b>반복을 끊는 판정은 이것으로 한다</b> — 청크보다 적으면 더 남은 것이 없다
 * @param deleted 실제로 지워진 건수. <b>로그에 남기는 것은 이것이다</b> — 남이 먼저 지운 것까지 내 몫으로 세지 않는다
 */
public record NotificationExpiry(int picked, int deleted) {}
