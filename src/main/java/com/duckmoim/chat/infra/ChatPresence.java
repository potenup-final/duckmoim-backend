package com.duckmoim.chat.infra;

import java.util.Set;

/**
 * 지금 그 방을 보고 있는 사람 (NT-07).
 *
 * <p><b>왜 필요한가.</b> 검증 기준이 「접속 중 멤버에게 알림이 생기지 않는다」인데, SSE 연결은 <b>그 연결을 받은 인스턴스의 메모리에만</b> 있다
 * ({@code ChatStreamService}). EC2 가 둘이고 ALB 가 사람을 갈라 보내므로, 로컬 연결만 세면 다른 인스턴스에 붙어 보고 있는 사람에게 알림이
 * 생긴다.
 *
 * <pre>
 * 지민  SSE 연결 ────▶ green        green.connections{3: [지민]}
 * 하늘  POST 전송 ───▶ blue         blue.connections{3: [하늘]}
 *                       └─ 지민이 보고 있는지 blue 는 모른다  ⚠️
 * </pre>
 *
 * <p>{@link ChatFanout} 이 푼 것과 같은 문제이고, 그래서 답도 같은 곳에 있다.
 *
 * <p><b>절대 던지지 않는다.</b> 이 포트의 장애가 메시지 전송의 500 이 되면 부가 기능의 장애가 본 기능을 죽인다 — {@code ChatFanout} 이 세운
 * 계약을 그대로 따른다.
 *
 * <p><b>못 읽으면 「아무도 안 본다」로 답한다.</b> 억제는 부속이고 알림은 본 기능이라 열리는 쪽으로 실패한다. 막는 쪽으로 실패하면 그 알림은 재시도도 없이 영영
 * 사라지지만, 열어 두면 보고 있던 사람이 알림을 하나 더 받을 뿐이다.
 *
 * <p><b>저장소가 아니다.</b> 여기 담기는 것은 「지금」뿐이고 지나면 스스로 사라진다. 재기동하면 비는 것이 맞다 — 그때는 열려 있던 연결도 함께 죽는다.
 *
 * <p><b>infra 에 산다.</b> {@code ChatFanout} 과 같은 배치다 (아키텍처 컨벤션 「infra · 저장소」).
 */
public interface ChatPresence {

  /** 한 사람이 그 방을 보기 시작했다. 이미 있으면 시각만 갱신된다. */
  void enter(long roomId, long userId);

  /** 한 사람이 그 방에서 나갔다. 없으면 아무 일도 하지 않는다. */
  void leave(long roomId, long userId);

  /**
   * 아직 보고 있다고 알린다. <b>하트비트가 30초마다 부른다</b> ({@code ChatStreamHeartbeat}).
   *
   * <p><b>이것이 없으면 죽은 인스턴스가 쥔 사람이 영영 「보는 중」으로 남는다.</b> 갱신이 멈추면 신선도 기준에서 밀려 저절로 빠진다 — 상태를 지우는 주체를 따로
   * 두지 않아도 되는 것이 시각으로 재는 방식의 값이다.
   *
   * @param userIds 이 인스턴스에 지금 붙어 있는 사람들. 비어 있으면 아무 일도 하지 않는다
   */
  void refresh(long roomId, Set<Long> userIds);

  /**
   * 지금 그 방을 보고 있는 사람들.
   *
   * <p>읽지 못하면 <b>빈 집합</b>이다. 예외로 올리지 않는다.
   */
  Set<Long> viewers(long roomId);
}
