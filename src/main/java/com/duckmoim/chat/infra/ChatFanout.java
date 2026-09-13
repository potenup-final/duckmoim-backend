package com.duckmoim.chat.infra;

import java.util.function.Consumer;

/**
 * 한 방의 사건을 <b>다른 인스턴스에 붙어 있는 사람</b>에게 넘긴다 (CH-10).
 *
 * <p><b>왜 필요한가.</b> EC2 가 둘이고 ALB 가 사람을 갈라 보낸다. SSE 연결은 그 연결을 받은 인스턴스의 메모리에만 있으므로, blue 에 붙은 사람이 보낸
 * 메시지를 green 에 붙은 사람에게 밀어줄 방법이 없다. {@code CH-10} 의 검증 기준이 「다른 인스턴스에 붙은 멤버의 메시지도 도착한다」 인 것이 이 문제를
 * 가리킨다.
 *
 * <p><b>저장소가 아니다.</b> 발행하는 순간 지금 듣고 있는 구독자에게 전달하고 끝이다. 아무도 안 듣고 있었으면 그대로 사라지고, 그래도 잃는 것이 없다 — 메시지의
 * 정본은 MySQL 이고 여기는 통로다. 끊긴 사람은 재연결 때 {@code CH-11}(STAR-114)이 메운다.
 *
 * <p><b>그래서 실패가 예외로 올라오지 않는다.</b> {@link #publish} 는 Redis 가 죽어 있어도 던지지 않는다. 이 포트의 장애가 메시지 전송의 500
 * 이 되면 부가 기능의 장애가 본 기능을 죽이는 구조가 된다 — {@code NT-01} 이 아웃박스로 막은 것과 같은 실수다. 던지지 않는 것이 구현의 재량이 아니라 <b>이
 * 인터페이스의 계약</b>이라 여기 적는다.
 *
 * <p><b>본문이 {@code String} 인 것은 아직 정하지 않았다는 뜻이다.</b> 무엇을 실어 보낼지는 SSE 이벤트의 모양이 정하고 그것은 {@code
 * CH-10}(STAR-113) 몫이다. 여기서 타입을 지어내면 그 타입이 다음 담당의 기준선이 되어, 정작 필요한 모양이 다를 때 되돌리기 어려워진다 — {@code
 * ChatErrorCode} 가 쓰지 않는 상수를 미리 두지 않은 것과 같은 판단이다.
 *
 * <p><b>infra 에 산다.</b> 저장소 인터페이스와 같은 배치다 (아키텍처 컨벤션 「infra · 저장소」). 포트를 domain 이나 service 에 두는 뒤집힌
 * 방향은 이 저장소가 이미 기각했다.
 */
public interface ChatFanout {

  /**
   * 한 방에 사건을 발행한다.
   *
   * <p><b>절대 던지지 않는다.</b> Redis 가 죽었거나 느려도 호출한 쪽은 그대로 진행한다. 실패는 로그로만 남는다.
   *
   * @param roomId 방 번호
   * @param payload 구독자에게 그대로 전달될 문자열. 모양은 STAR-113 이 정한다
   */
  void publish(long roomId, String payload);

  /**
   * 한 방의 사건을 받기 시작한다.
   *
   * <p>돌려받은 것을 닫으면 구독이 끊긴다. <b>닫지 않으면 리스너가 쌓인다</b> — SSE 연결이 끝날 때 반드시 닫아야 하고, 그 책임은 이 포트를 부르는
   * STAR-113 에 있다.
   *
   * @param roomId 방 번호
   * @param handler 발행된 문자열을 받는다. <b>Redis 구독 스레드에서 불린다</b>
   */
  ChatFanoutSubscription subscribe(long roomId, Consumer<String> handler);
}
