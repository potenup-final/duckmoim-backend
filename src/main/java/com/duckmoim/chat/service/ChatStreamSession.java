package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.MessageEvent;

/**
 * 열려 있는 SSE 연결 하나 (CH-10).
 *
 * <p><b>{@code SseEmitter} 를 service 가 알지 않기 위한 포트다.</b> 그 타입은 HTTP 응답을 붙들고 있는 물건이라 presentation 의
 * 것이고, service 는 「연결에 사건을 민다」와 「연결을 닫는다」 둘만 알면 된다 — {@code ChatFanout} 이 {@code RedisTemplate} 을 같은
 * 방식으로 가린 것과 같은 배치다.
 *
 * <p>그 대가로 <b>스트림 로직을 MockMvc 없이 단위 테스트할 수 있다.</b> 구독·해제·퇴장 끊기를 검사하는 데 톰캣이 필요하지 않다.
 *
 * <p><b>구현은 두 메서드 다 예외를 던지지 않는다.</b> 연결이 이미 죽은 뒤에 미는 일이 정상 경로에 있다 — 브라우저를 닫은 순간과 다음 메시지가 오는 순간 사이가
 * 항상 열려 있기 때문이다. 그때마다 예외가 올라오면 <b>한 사람의 죽은 연결이 그 방 전체의 팬아웃을 끊는다.</b>
 */
public interface ChatStreamSession {

  /** 사건 하나를 민다. 실패해도 던지지 않는다 — 끊긴 연결은 {@link #close} 로 정리된다. */
  void send(MessageEvent event);

  /**
   * 살아 있다는 신호를 보낸다.
   *
   * <p>{@link #send} 와 갈라 둔 이유는 <b>화면에 아무 일도 일으키지 않아야</b> 하기 때문이다. 사건으로 보내면 클라이언트가 빈 말풍선을 그리게 된다.
   *
   * <p>실패해도 던지지 않는다 — 이 신호가 끊긴 연결을 발견하는 자리이고, 발견은 예외가 아니라 정리로 이어져야 한다.
   */
  void beat();

  /**
   * 되돌려주기에 너무 많이 밀렸다고 알린다 (CH-11).
   *
   * <p><b>{@link #send} 와 갈라 둔 이유는 이것이 말풍선이 아니기 때문이다.</b> 화면에 그릴 것이 없고, 클라이언트는 이 신호를 받으면 목록
   * API(CH-09)로 따라잡는다 — 그쪽이 이미 커서 페이징이라 새로 만들 것이 없다.
   *
   * <p><b>재개 지점을 앞으로 밀지 않아야 한다.</b> 이 신호에 재개 지점을 실으면 다음 재연결이 빠진 구간을 건너뛰고 시작해 <b>구멍이 굳는다.</b> 구현은
   * 선로의 {@code id} 를 건드리지 않는다.
   *
   * <p>실패해도 던지지 않는다 — {@link #send} 와 같다.
   *
   * @param fromMessageId 클라이언트가 마지막으로 받았다고 알려 온 번호. 어디까지 거슬러 올라가면 되는지의 기준이다
   */
  void sendGap(Long fromMessageId);

  /** 연결을 끝낸다. 두 번 불러도 안전하다. */
  void close();
}
