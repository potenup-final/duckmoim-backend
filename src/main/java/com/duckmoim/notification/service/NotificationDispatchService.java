package com.duckmoim.notification.service;

import com.duckmoim.common.domain.NotificationOutbox;
import com.duckmoim.common.infra.NotificationOutboxRepository;
import com.duckmoim.notification.domain.Notification;
import com.duckmoim.notification.domain.NotificationOutboxDlq;
import com.duckmoim.notification.infra.NotificationOutboxDlqRepository;
import com.duckmoim.notification.infra.NotificationRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아웃박스 한 건을 알림함으로 옮긴다 (NT-02 · NT-03).
 *
 * <p><b>주기와 반복은 여기 없다.</b> {@code NotificationDispatchBatch} 가 진다 — 이 클래스의 메서드가 트랜잭션 경계이고 같은 클래스
 * 안에서 자기를 부르면 프록시를 지나지 않아 {@code @Transactional} 이 걸리지 않는다. 마감 배치가 빈을 둘로 나눈 것과 같은 이유다.
 *
 * <p><b>한 건이 한 트랜잭션이다.</b> 청크를 한 트랜잭션에 담으면, 한 건이 DB 오류로 실패한 순간 그 트랜잭션에 롤백 표시가 붙어 <b>같은 청크의 나머지가 함께
 * 사라진다.</b> 실패 기록도 그 트랜잭션에 쓸 수 없다.
 *
 * <p><b>그래서 실패 기록이 별 메서드다.</b> {@link #dispatch} 가 실패해 롤백된 뒤에 {@link #recordFailure} 가 새 트랜잭션에서 시도
 * 횟수를 올린다. 같은 트랜잭션에서 하려 하면 롤백이 그 기록까지 지운다.
 */
@Service
public class NotificationDispatchService {

  private final NotificationOutboxRepository outboxRepository;
  private final NotificationRepository notificationRepository;
  private final NotificationOutboxDlqRepository dlqRepository;

  /**
   * 실패 뒤 다음 시도까지 기다리는 간격 (NT-03).
   *
   * <p>세 값을 두었는데 지금 쓰이는 것은 앞의 둘이다 — 시도 상한이 셋이라 세 번째 실패는 기다리지 않고 DLQ 로 간다. 5배수 모양을 그대로 남긴 것은 상한을 넷으로
   * 올릴 때 값을 다시 정하지 않게 하려는 것이다.
   */
  private final List<Duration> backoff;

  /** 이만큼 시도하고 못 보내면 DLQ 로 옮긴다 (NT-03). */
  private final int maxAttempts;

  /**
   * 선점한 건을 남이 못 집게 막아 두는 시간 (NT-04).
   *
   * <p><b>짧으면 리스가 만료된 건을 남이 집고, 길면 죽은 워커가 쥔 건이 오래 멈춰 있다.</b> 한 건의 발송이 알림 INSERT 하나라 밀리초 단위인데, 그보다
   * 충분히 길고 가장 짧은 백오프(1분)보다는 짧아야 재시도 간격과 뒤섞이지 않는다.
   */
  private final Duration lease;

  public NotificationDispatchService(
      NotificationOutboxRepository outboxRepository,
      NotificationRepository notificationRepository,
      NotificationOutboxDlqRepository dlqRepository,
      @Value("${duckmoim.notification.worker.backoff}") List<Duration> backoff,
      @Value("${duckmoim.notification.worker.max-attempts}") int maxAttempts,
      @Value("${duckmoim.notification.worker.lease}") Duration lease) {

    this.outboxRepository = outboxRepository;
    this.notificationRepository = notificationRepository;
    this.dlqRepository = dlqRepository;
    this.backoff = backoff;
    this.maxAttempts = maxAttempts;
    this.lease = lease;
  }

  /**
   * 지금 보낼 수 있는 건을 <b>집어서 내 것으로 표시하고</b> 번호를 돌려준다 (NT-04).
   *
   * <p><b>읽기만 하던 것이 쓰기가 됐다.</b> 예전에는 조회 전용이었고 그래서 두 워커가 같은 목록을 받았다 — 200건을 워커 둘이 돌렸을 때 162건이 알림
   * INSERT 에서 유니크 제약에 부딪혀 롤백됐다(실측). 이제 잠그고 읽은 뒤 리스를 적으므로 그 목록이 갈린다.
   *
   * <p><b>잠금과 리스가 둘 다 필요하다.</b> 잠금은 이 트랜잭션이 끝나면 풀리는데 발송은 건마다 다른 트랜잭션이라, 잠금만으로는 그 사이에 남이 집는다. 리스는
   * 잠금이 풀린 뒤에도 남는 표시다. 반대로 리스만 두고 잠그지 않으면 <b>둘이 동시에 읽고 둘 다 리스를 쓰는</b> 경쟁이 남는다.
   *
   * <p><b>엔티티가 아니라 번호를 돌려준다.</b> 건마다 트랜잭션이 따로라, 여기서 읽은 엔티티는 부르는 쪽에서 이미 준영속이다. 번호만 넘기고 각 트랜잭션이 다시
   * 읽는다.
   *
   * <p>{@code Pageable} 을 시그니처에 두지 않는다 — 아키텍처 컨벤션이 service 의 공개 시그니처에 Spring Data 타입을 금지했다.
   */
  @Transactional
  public List<Long> claimSendableIds(LocalDateTime nowInUtc, int chunk) {
    List<NotificationOutbox> claimed =
        outboxRepository.findSendableForUpdate(nowInUtc, maxAttempts, PageRequest.ofSize(chunk));

    LocalDateTime leaseUntil = nowInUtc.plus(lease);
    claimed.forEach(outbox -> outbox.claim(leaseUntil));

    return claimed.stream().map(NotificationOutbox::getId).toList();
  }

  /**
   * 한 건을 알림함으로 옮기고 보냈다고 적는다 (NT-02).
   *
   * <p><b>이미 알림이 있으면 만들지 않는다.</b> 알림 저장과 상태 변경이 한 트랜잭션이라 크래시로는 이 상태가 생기지 않는다 — 생기는 자리는 사람이 손을 댔거나
   * 나중에 DLQ 를 다시 태우는 경로다. 그때 유니크 제약에 걸리면 트랜잭션에 롤백 표시가 붙어 상태를 바꿀 수 없고 그 행이 영영 다시 집히므로, 제약을 뒷막이로 두고
   * 먼저 물어본다.
   *
   * <p><b>이 확인이 워커 둘의 경쟁을 막지는 못한다.</b> 둘이 동시에 물어보면 둘 다 「없다」를 받는다. 그 경쟁의 결말은 유니크 제약이 정하고, 진 쪽은 아래
   * {@link #recordFailure} 가 「남이 보냈다」로 넘긴다.
   *
   * <p><b>남이 이미 처리한 건은 그냥 넘어간다.</b> 선점이 없어 (NT-04) 두 워커가 같은 건을 집을 수 있고, 그것은 사고가 아니라 지금 구조에서 정상으로
   * 일어나는 일이다. 여기서 상태를 확인하지 않고 도메인의 전이를 부르면 {@code IllegalStateException} 이 나고, 그 예외가 배치의 주기 전체를
   * 끝낸다.
   *
   * <p>행이 없을 때도 같다 — 집은 뒤 DLQ 로 옮겨졌거나 다른 워커가 처리한 것이다.
   *
   * @return 알림을 새로 만들었으면 {@code true}. 남이 처리했거나 행이 사라졌으면 {@code false}
   */
  @Transactional
  public boolean dispatch(Long outboxId) {
    NotificationOutbox outbox = outboxRepository.findById(outboxId).orElse(null);

    if (outbox == null || !outbox.isPending()) {
      return false;
    }

    if (notificationRepository.existsByOutboxId(outboxId)) {
      outbox.markSent();
      return false;
    }

    notificationRepository.save(
        Notification.of(
            outboxId,
            outbox.getRecipientId(),
            outbox.getKind(),
            outbox.getPostId(),
            outbox.getCommentId()));

    outbox.markSent();

    return true;
  }

  /**
   * 실패를 적고 다음 시도를 미룬다. 시도를 다 썼으면 DLQ 로 옮긴다 (NT-03).
   *
   * <p><b>새 트랜잭션이다.</b> {@link #dispatch} 가 롤백된 뒤에 불리므로 그 트랜잭션과 이어지지 않는다.
   *
   * <p><b>옮기면서 원본을 지운다.</b> 명세 문면이 「별도 표로 옮기고」이고, 남겨 두면 워커가 10초마다 훑는 표에 죽은 건이 쌓인다.
   *
   * <p><b>남이 보낸 건에는 실패를 적지 않는다.</b> 유니크 제약에 걸려 {@link #dispatch} 가 롤백됐다는 것은 <b>다른 워커가 그 알림을
   * 만들었다</b>는 뜻이라 우리 실패가 아니다. 그걸 세면 전달된 알림의 시도 횟수가 올라가고, 세 번 겹치면 <b>이미 보낸 알림이 DLQ 로 간다</b> — 그 표는
   * 「못 보낸 것」이라는 뜻이므로 장부가 거짓이 된다.
   *
   * @return 시도를 다 써서 DLQ 로 옮겼으면 {@code true}
   */
  @Transactional
  public boolean recordFailure(Long outboxId, LocalDateTime nowInUtc) {
    NotificationOutbox outbox = outboxRepository.findById(outboxId).orElse(null);

    if (outbox == null || !outbox.isPending()) {
      return false;
    }

    outbox.failed(nowInUtc.plus(backoffFor(outbox.getAttempts())));

    if (!outbox.hasExhausted(maxAttempts)) {
      return false;
    }

    dlqRepository.save(NotificationOutboxDlq.of(outbox, nowInUtc));
    outboxRepository.delete(outbox);

    return true;
  }

  /**
   * 이번 실패 뒤 기다릴 간격.
   *
   * <p>실패 횟수가 설정한 값보다 많아지면 마지막 값을 계속 쓴다. 상한을 올렸는데 간격을 안 늘린 경우를 예외로 만들지 않는다.
   */
  private Duration backoffFor(int attemptsBeforeThisFailure) {
    int index = Math.min(attemptsBeforeThisFailure, backoff.size() - 1);

    return backoff.get(index);
  }
}
