package com.duckmoim.notification.service;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.infra.NotificationMuteRepository;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종류별 수신 설정을 읽고 쓴다 (NT-11).
 *
 * <p><b>「끈 종류」로 말한다.</b> 화면은 토글 셋을 그리지만 저장은 끈 것만 남기므로 (V807), 켜짐·꺼짐 셋을 이 계층까지 들고 오면 같은 상태를 두 모양으로
 * 다루게 된다. 켜짐 셋으로 뒤집는 것은 presentation 이 한다.
 *
 * <p><b>저장은 지우고 다시 넣는다.</b> 계약이 {@code PUT}(전체 수정)이라 들어온 것이 곧 새 상태다. 무엇이 늘고 줄었는지 따져 부분만 고치면 계산이 하나
 * 더 생기는데, 한 사람의 행이 많아야 종류 수(셋)다.
 *
 * <p><b>그 저장의 트랜잭션은 {@link NotificationMuteWriter} 에 있다</b> (PR #148 리뷰). 여기는 충돌을 잡아 다시 시도하는 자리이고,
 * 진 트랜잭션은 <b>이미 롤백된 뒤</b>라 같은 트랜잭션 안에서는 다시 시도할 수 없다 — {@code ChatMessageSendService} / {@code
 * ChatMessageWriter} 가 유니크 위반 때문에 나뉜 것과 같은 배치다.
 *
 * <p><b>여기서 「누구의 설정인가」를 판정하지 않는다.</b> 회원번호가 경로가 아니라 인증 주체에서 오므로 남의 것을 가리킬 길이 없다 — 알림함이 {@code I-24}
 * 를 지키는 방식과 같다 (API-설계.md 「5. 결정 사항」 D-14).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

  /**
   * 충돌로 죽었을 때 다시 시도하는 횟수 (첫 시도 포함).
   *
   * <p><b>셋이면 충분하다.</b> 진 트랜잭션은 롤백된 뒤라 다음 시도는 이긴 쪽이 커밋을 마친 상태에서 시작한다 — 같은 사람의 요청 둘이 겹치는 상황이라 줄을 선
   * 것이 셋을 넘을 일이 없다. 넘으면 그것은 재시도로 풀 문제가 아니라 알아야 할 고장이라 그대로 올린다.
   *
   * <p><b>기다렸다 다시 시도하지 않는다.</b> 데드락은 진 쪽이 이미 풀려난 뒤에 알려지므로 붙들고 잘 이유가 없고, 이 자리는 사용자의 요청 스레드다.
   */
  private static final int MAX_ATTEMPTS = 3;

  private final NotificationMuteRepository notificationMuteRepository;
  private final NotificationMuteWriter notificationMuteWriter;

  /**
   * 이 사람이 끈 종류.
   *
   * <p><b>한 번도 만지지 않았으면 빈 집합이다</b> — 행이 없는 것이 곧 「전부 받는다」다 (V807). 가입 시점에 아무것도 만들지 않는 것이 그래서 가능하다.
   */
  @Transactional(readOnly = true)
  public Set<NotificationKind> findMutedKinds(Long userId) {
    return copyOf(notificationMuteRepository.findMutedKinds(userId));
  }

  /**
   * 끈 종류를 이 목록으로 바꾼다.
   *
   * <p><b>같은 것을 두 번 보내도 결과가 같다.</b> 지우고 다시 넣으므로 {@code uq_notification_mute} 에 부딪히지 않는다 — <b>다만 그
   * 「지우고」가 실제로 먼저 나가야 성립한다</b> (PR #148 리뷰). 파생 삭제는 {@code DELETE} 를 flush 까지 미루는데 {@code IDENTITY}
   * 인 {@code INSERT} 는 즉시 나가서, 이미 끈 종류를 다시 보내면 그 제약에 걸려 500 이었다. 지금은 저장소가 벌크 삭제라 순서가 문장 자체로 지켜진다.
   *
   * <p><b>이미 만들어진 알림은 건드리지 않는다.</b> 설정은 앞으로 올 것에만 걸린다 — 티켓의 완료 조건이 그것을 명시적으로 막는다. 여기서 지우면 끈 사람이 「어제
   * 온 알림이 사라졌다」를 겪는다.
   *
   * <p><b>같은 사람의 요청이 동시에 들어오면 한쪽이 죽을 수 있어 다시 시도한다</b> (PR #148 리뷰). 토글을 연달아 누르면 {@code PUT} 이 겹치는데,
   * 「지우고 다시 넣기」가 <b>MySQL 의 전형적인 데드락 모양</b>이다.
   *
   * <pre>
   * 끈 것이 없는 사람이 토글을 더블클릭
   *   A  DELETE  (맞는 행이 없어 갭 락만 잡는다)
   *   B  DELETE  (갭 락끼리는 호환이라 함께 잡힌다)
   *   A  INSERT  → 삽입 의도 락이 B 의 갭 락과 부딪혀 기다린다
   *   B  INSERT  → 반대쪽도 마찬가지          ⇒ 데드락, 진 쪽이 500
   * </pre>
   *
   * <p><b>행이 없을 때 가장 잘 터진다.</b> 행이 있으면 서로의 X 락에 막혀 차례로 줄을 서서 데드락까지 가지 않는다. 그래서 <b>설정을 한 번도 안 만진 사람의
   * 첫 토글</b>이 가장 위험하다.
   *
   * <p><b>{@code DataIntegrityViolationException} 도 함께 잡는다.</b> 남이 먼저 커밋하면 내 {@code DELETE} 가 못 본
   * 행을 내가 다시 넣게 되는데, 다시 시도하면 그때는 그 행이 보여서 지워지고 깨끗하게 들어간다.
   *
   * <p><b>다시 시도하는 것이 안전한 이유는 {@code PUT} 이기 때문이다.</b> 들어온 것이 곧 새 상태라 몇 번을 돌려도 같은 답이고, 중간 상태가 남지 않는다
   * — 진 트랜잭션은 통째로 롤백된 뒤다.
   *
   * <p><b>회원 행을 {@code FOR UPDATE} 로 잠그는 길도 있었다.</b> 그쪽이 확실하지만 알림 설정이 identity 표를 잠그는 결합이 생기고, 이 표의
   * 행이 한 사람당 많아야 셋이라 부딪히는 창이 짧다 — 드문 충돌에 상시 비용을 얹지 않는다.
   */
  public void replaceMutedKinds(Long userId, Set<NotificationKind> mutedKinds) {
    for (int attempt = 1; ; attempt++) {
      try {
        notificationMuteWriter.replace(userId, mutedKinds);
        return;
      } catch (PessimisticLockingFailureException | DataIntegrityViolationException e) {
        if (attempt == MAX_ATTEMPTS) {
          throw e;
        }

        // 회원번호는 남기되 무엇을 껐는지는 남기지 않는다. 몇 번째였는지가 관측할 값이다.
        log.warn(
            "[NotificationSettingService.replaceMutedKinds] 설정 저장 충돌 — 다시 시도한다. userId={} attempt={} cause={}",
            userId,
            attempt,
            e.getClass().getSimpleName());
      }
    }
  }

  /** 빈 목록에서도 {@code EnumSet} 을 만들려면 타입을 줘야 한다. */
  private static Set<NotificationKind> copyOf(Iterable<NotificationKind> kinds) {
    Set<NotificationKind> copy = EnumSet.noneOf(NotificationKind.class);
    kinds.forEach(copy::add);

    return copy;
  }
}
