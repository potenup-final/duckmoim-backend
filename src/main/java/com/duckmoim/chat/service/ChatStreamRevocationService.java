package com.duckmoim.chat.service;

import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.chat.infra.ChatRoomSummary;
import com.duckmoim.identity.domain.UserWithdrawn;
import com.duckmoim.safety.domain.UserSanctioned;
import com.duckmoim.safety.service.SanctionQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 더는 대화를 받으면 안 되는 회원의 열린 스트림을 끊는다 (CH-20 · AU-11 · STAR-148).
 *
 * <p><b>관문은 요청이 올 때만 돈다.</b> 스트림은 요청이 한 번뿐이라, 연결한 뒤에 제재된 사람은 다시 판정받을 일 없이 타임아웃(5분)까지 대화를 받았다
 * (QA-AUTH-02).
 *
 * <pre>
 * 전  21:31:54 스트림 연결 → 21:32:14 BANNED → 21:32:35 새 메시지 도착 → 21:36:55 타임아웃
 * 후  제재가 커밋되면 그 자리에서 끊긴다. 다시 붙으면 관문이 403 이다
 * </pre>
 *
 * <p><b>퇴장과 같은 끊기를 쓴다.</b> 방마다 {@link ChatStreamService#disconnect} 를 부르면 이 인스턴스의 연결은 바로 닫히고, 다른
 * 인스턴스에는 퇴장 사건으로 전파돼 거기서 닫힌다. 새 팬아웃 종류를 만들지 않는다 — 퇴장 사건이 하는 일이 정확히 「그 사람의 연결만 끊기」다.
 *
 * <p><b>커밋 뒤에 돈다</b> ({@code CompanionPostOpened} 의 구독 규약 ②). 연결 종료와 Redis 발행은 되돌릴 수 없는 바깥 일이라, 제재가
 * 롤백되면 아무도 끊기지 않아야 한다. {@code REQUIRES_NEW} 를 걸지 않은 것은 여기서 쓰는 것이 없어서다 — 읽기는 커밋된 뒤의 값을 그대로 읽는다.
 *
 * <p><b>던지지 않는다.</b> 제재는 이미 커밋됐다. 끊기가 실패해도 남는 것은 타임아웃까지의 창이고 (고치기 전과 같다), 관리자에게 실패를 돌려줄 일이 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamRevocationService {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatStreamService chatStreamService;
  private final SanctionQueryService sanctionQueryService;

  /**
   * 비공개 읽기가 막힌 제재면 끊는다.
   *
   * <p><b>종류를 여기서 가르지 않는다.</b> 도메인-모델링.md 「6. 라이프사이클」의 제재 표가 비공개 읽기를 막는 것을 {@code BANNED} 하나로 정했지만,
   * 채팅이 그 이름을 적으면 표가 바뀌는 날 두 곳이 갈린다. 관문({@code SanctionGateInterceptor})이 묻는 같은 판정에 묻는다.
   *
   * <p>기간 정지 · 나이 확인 · 경고는 채팅방을 계속 읽을 수 있어 스트림도 둔다. 쓰기는 관문이 막는다 (CH-20).
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onSanctioned(UserSanctioned sanctioned) {
    if (sanctionQueryService.canReadPrivate(sanctioned.userId())) {
      return;
    }

    disconnectEverywhere(sanctioned.userId());
  }

  /**
   * 탈퇴하면 끊는다 (AU-11).
   *
   * <p><b>제재와 같은 구멍이다.</b> 탈퇴는 토큰을 끊지만 토큰은 요청이 올 때만 검사된다 — 열린 스트림은 탈퇴 뒤에도 대화를 받았다 (추가 QA, 재연결은
   * 401).
   *
   * <p><b>탈퇴한 사람은 방 멤버 행이 남는다</b> (API-설계 「2-11. 채팅 (Chat) · 2차」 — 목록에서 자리표시자로 남는다). 그래서 멤버인 방 목록으로
   * 그대로 찾을 수 있다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onWithdrawn(UserWithdrawn withdrawn) {
    disconnectEverywhere(withdrawn.userId());
  }

  /** 그 회원이 멤버인 모든 방에서 연결을 끊는다. 방 목록은 방 목록 화면(CH-05)과 같은 조회다. */
  private void disconnectEverywhere(Long userId) {
    try {
      chatRoomRepository.findSummariesForMember(userId).stream()
          .map(ChatRoomSummary::roomId)
          .forEach(roomId -> chatStreamService.disconnect(roomId, userId));
    } catch (RuntimeException e) {
      log.warn(
          "[ChatStreamRevocationService.disconnectEverywhere] 스트림 끊기 실패 — 타임아웃까지 남는다. userId={} cause={}",
          userId,
          e.getClass().getSimpleName());
    }
  }
}
