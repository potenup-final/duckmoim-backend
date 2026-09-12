package com.duckmoim.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 열려 있는 연결에 주기적으로 신호를 보낸다 (CH-10).
 *
 * <p><b>없으면 대화 없는 방의 연결이 1분마다 끊긴다.</b> ALB 의 유휴 타임아웃이 기본 60초이고, 그 사이 한 바이트도 안 흐르면 ALB 가 먼저 끊는다 —
 * 채팅방은 한 시간도 조용할 수 있다. 브라우저가 다시 붙기는 하지만 1분마다 재연결이 도는 것은 정상이 아니다.
 *
 * <p><b>사건이 아니라 주석을 보낸다.</b> SSE 는 {@code :} 로 시작하는 줄을 주석으로 읽고 {@code EventSource} 가 그것을 이벤트로 올리지
 * 않는다 — 화면에 아무 일도 안 일어나면서 선로만 살아 있다.
 *
 * <p><b>끊긴 연결을 걷어내는 일도 여기서 일어난다.</b> 브라우저를 닫아도 서버는 다음에 쓸 때까지 그 사실을 모르는데, 하트비트가 그 「다음에 쓸 때」다 — 쓰기가
 * 실패하면 {@code SseEmitter} 의 오류 콜백이 돌아 목록에서 빠진다.
 *
 * <p><b>주기를 30초로 둔 근거</b> — ALB 60초의 절반이다. 한 번 놓쳐도 다음 것이 60초 안에 들어온다.
 *
 * <p>주기 설정을 {@code cron} 이 아니라 고정 지연으로 둔 것은 이 작업이 「몇 시에」가 아니라 「얼마나 자주」의 문제라서다. 마감 배치·알림 워커와 갈리는
 * 지점이고, 테스트에서 끄는 방법은 같다 ({@code build.gradle} 의 systemProperty).
 */
@Component
@RequiredArgsConstructor
public class ChatStreamHeartbeat {

  private final ChatStreamService chatStreamService;

  @Scheduled(
      fixedDelayString = "${duckmoim.chat.stream.heartbeat-interval-ms}",
      initialDelayString = "${duckmoim.chat.stream.heartbeat-interval-ms}")
  public void beat() {
    chatStreamService.heartbeat();
  }
}
