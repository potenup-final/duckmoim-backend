package com.duckmoim.chat.infra;

/**
 * {@link ChatFanout#subscribe} 를 되돌리는 손잡이.
 *
 * <p>{@link AutoCloseable} 을 그대로 쓰지 않는 이유는 그쪽 {@code close()} 가 {@code Exception} 을 던지기 때문이다. 구독을
 * 끊는 일은 실패할 것이 없는데 부르는 쪽마다 {@code try-catch} 가 생긴다. 좁혀서 다시 선언한다.
 */
@FunctionalInterface
public interface ChatFanoutSubscription extends AutoCloseable {

  /** 구독을 끊는다. 두 번 불러도 안전하다. */
  @Override
  void close();
}
