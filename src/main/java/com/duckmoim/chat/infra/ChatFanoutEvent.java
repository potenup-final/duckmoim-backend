package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.MessageEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 팬아웃 통로에 실리는 한 건 (CH-10 · CH-04 · CH-12 · AD-09).
 *
 * <p><b>통로에 세 종류가 흐른다.</b> 새 메시지, 상태가 바뀐 메시지, 퇴장이다. 채널을 나누는 방법도 있었지만 그러면 <b>방마다 구독이 여럿</b>이 되고, 그
 * 수명을 따로 관리해야 한다 — 하나만 닫히는 날 조용히 새는 쪽이 생긴다. 봉투 하나에 종류를 적어 보내면 구독은 방마다 하나로 남는다.
 *
 * <p><b>상태 변경을 새 메시지와 가른 이유는 선로의 {@code id} 다</b> (STAR-147). 새 메시지는 번호가 늘어나는 순서로 나가 그 번호가 곧 재연결
 * 위치인데, 지운 메시지는 <b>옛 번호</b>다. 같은 종류로 보내면 재연결 위치가 뒤로 밀린다 — 받는 쪽이 둘을 다르게 써야 해서 봉투에서 갈랐다.
 *
 * <p><b>이 타입이 infra 에 있는 이유는 선로 형식이기 때문이다.</b> service 는 「메시지가 왔다」 · 「누가 나갔다」만 알면 되고, 그것이 JSON 의 어떤
 * 필드로 실려 가는지는 여기서 끝난다 — {@code MessageEvent} 가 domain 인 것과 갈리는 자리다. 그쪽은 셋이 다 만지는 값이고 이쪽은 통로의 포장이다.
 *
 * <p><b>판독한 쪽이 종류를 보고 갈라야 한다.</b> {@code type} 이 모르는 값이면 그 한 건을 버린다 — 종류가 느는 날 옛 인스턴스가 새 사건을 받는 창이
 * 배포마다 열리기 때문이다.
 *
 * @param type 무엇이 일어났나
 * @param message {@link Type#MESSAGE} · {@link Type#MESSAGE_CHANGED} 일 때만 찬다
 * @param leftUserId {@link Type#MEMBER_LEFT} 일 때만 찬다. 그 사람의 연결만 끊는다
 */
public record ChatFanoutEvent(Type type, MessageEvent message, Long leftUserId) {

  public enum Type {
    /** 새 메시지가 저장됐다 (CH-07 → CH-10). */
    MESSAGE,

    /** 이미 나간 메시지의 상태가 바뀌었다 — 지웠거나 (CH-12) 가렸다 (AD-09). */
    MESSAGE_CHANGED,

    /** 누가 방을 나갔다 (CH-04 → CH-18). */
    MEMBER_LEFT
  }

  public static ChatFanoutEvent message(MessageEvent event) {
    return new ChatFanoutEvent(Type.MESSAGE, event, null);
  }

  public static ChatFanoutEvent messageChanged(MessageEvent event) {
    return new ChatFanoutEvent(Type.MESSAGE_CHANGED, event, null);
  }

  public static ChatFanoutEvent memberLeft(Long userId) {
    return new ChatFanoutEvent(Type.MEMBER_LEFT, null, userId);
  }

  /**
   * {@code @JsonIgnore} 가 없으면 선로가 조용히 깨진다.
   *
   * <p>Jackson 은 {@code isXxx()} 를 <b>불리언 프로퍼티로 자동 인식</b>한다. {@code isMessage()} 는 이름이 {@code
   * message} 라 <b>레코드 컴포넌트 {@code message} 와 같은 칸을 놓고 다투고</b>, 직렬화 결과에 {@code "message": true} 가
   * 실린다. 받는 쪽은 그 자리에서 {@code MessageEvent} 를 만들려다 {@code MismatchedInputException} 이다.
   *
   * <p><b>증상이 「모든 메시지가 조용히 사라진다」였다.</b> 예외는 {@code decode} 가 삼켜 로그에만 남고 구독은 멀쩡히 살아 있다.
   */
  @JsonIgnore
  public boolean isMemberLeft() {
    return type == Type.MEMBER_LEFT && leftUserId != null;
  }

  /** {@link #isMemberLeft} 의 각주를 함께 읽는다 — 이름이 부딪히는 쪽이 이 메서드다. */
  @JsonIgnore
  public boolean isMessage() {
    return type == Type.MESSAGE && message != null;
  }

  /** {@link #isMemberLeft} 의 각주와 같은 이유로 {@code @JsonIgnore} 다. */
  @JsonIgnore
  public boolean isMessageChanged() {
    return type == Type.MESSAGE_CHANGED && message != null;
  }
}
