package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ExifStatus;
import com.duckmoim.chat.infra.ChatImageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EXIF 워커의 DB 쪽 걸음들 (CH-16).
 *
 * <p><b>주기와 저장소 호출은 {@code ChatImageExifWorker} 가 지고, 여기는 각 걸음을 짧은 트랜잭션으로 진다.</b> {@code
 * NotificationDispatchService} / {@code NotificationDispatchBatch} 와 같은 배치다 — 10MB 내려받기와 업로드를 트랜잭션
 * 안에서 하면 그 시간 내내 DB 커넥션을 쥔다.
 *
 * <pre>
 * claimProcessableIds   잠그고 읽은 뒤 리스를 적는다     (트랜잭션)
 * findTarget            아직 벗길 대상인가              (읽기)
 * (워커)                 내려받기 · 벗기기 · 조건부 덮어쓰기  (트랜잭션 밖)
 * markStripped          벗겼다                          (트랜잭션)
 * recordFailure         실패 · 백오프 · 다 쓰면 FAILED   (트랜잭션)
 * </pre>
 *
 * <p><b>⚠️ 어느 걸음도 버전을 올리지 않는다.</b> 워커는 확정 직후, 사용자가 보내는 바로 그 몇 초 사이에 돈다 — 버전을 올리면 전송의 {@code attach}
 * 가 버전 충돌로 <b>정상 사용자에게 400</b> 을 준다. 그래서 전부 {@code exif_*} 열만 쓰는 조건부 UPDATE 이고, 엔티티를 고쳐 저장하지 않는다.
 */
@Service
public class ChatImageExifService {

  private final ChatImageRepository chatImageRepository;
  private final Clock clock;
  private final List<Duration> backoff;
  private final int maxAttempts;
  private final Duration lease;

  public ChatImageExifService(
      ChatImageRepository chatImageRepository,
      Clock clock,
      @Value("${duckmoim.chat.image.exif.backoff}") List<Duration> backoff,
      @Value("${duckmoim.chat.image.exif.max-attempts}") int maxAttempts,
      @Value("${duckmoim.chat.image.exif.lease}") Duration lease) {

    this.chatImageRepository = chatImageRepository;
    this.clock = clock;
    this.backoff = backoff;
    this.maxAttempts = maxAttempts;
    this.lease = lease;
  }

  /**
   * 벗길 사진을 청크만큼 선점한다 (ADR 0008).
   *
   * <p><b>잠금과 리스가 둘 다 필요하다.</b> 잠금은 이 트랜잭션이 끝나면 풀리는데 처리는 그 뒤에 일어나 그 사이에 남이 집는다. 반대로 리스만 두고 잠그지 않으면
   * 둘이 동시에 읽고 둘 다 리스를 쓴다.
   *
   * <p><b>선점은 시도가 아니라 실패 횟수를 올리지 않는다.</b> 올리면 성공한 처리도 재시도를 하나 까먹는다.
   */
  @Transactional
  public List<Long> claimProcessableIds(LocalDateTime nowInUtc, int chunk) {
    List<Long> ids =
        chatImageRepository
            .findExifProcessableForUpdate(nowInUtc, PageRequest.ofSize(chunk))
            .stream()
            .map(ChatImage::getId)
            .toList();

    if (!ids.isEmpty()) {
      chatImageRepository.leaseExif(ids, nowUtc().plus(lease));
    }
    return ids;
  }

  /**
   * 아직 벗길 대상인가.
   *
   * <p><b>선점과 처리 사이에 상황이 바뀐다.</b> 고아 정리가 {@code DELETING} 으로 못박았을 수 있고, 다른 워커가 리스가 풀린 뒤 이미 벗겼을 수
   * 있다. 그러면 빈 값이고 워커는 조용히 넘어간다 — 실패가 아니다.
   */
  @Transactional(readOnly = true)
  public Optional<ExifTarget> findTarget(Long imageId) {
    return chatImageRepository
        .findById(imageId)
        .filter(image -> image.getExifStatus() == ExifStatus.PENDING)
        .filter(
            image ->
                image.getStatus() == ChatImageStatus.CONFIRMED
                    || image.getStatus() == ChatImageStatus.ATTACHED)
        .map(image -> new ExifTarget(image.getId(), image.getObjectKey()));
  }

  /**
   * 벗겼다고 적는다. 이 뒤로 CH-15 가 서명을 발급할 수 있다.
   *
   * @return 이번에 적었으면 {@code true}. 그 사이 정리됐거나 남이 먼저 적었으면 {@code false}
   */
  @Transactional
  public boolean markStripped(Long imageId) {
    return chatImageRepository.markExifStripped(imageId) == 1;
  }

  /**
   * 일시적 실패를 적는다. 재시도가 남았으면 백오프 뒤로 미루고, 다 썼으면 {@code FAILED} 로 굳힌다.
   *
   * <p><b>{@code FAILED} 는 영구히 보여주지 않는다.</b> 원본에 좌표가 남은 채라서다 — 그래서 일시 장애 한 번에 굳히지 않고 재시도를 둔다.
   *
   * @return 재시도를 다 써서 {@code FAILED} 가 됐으면 {@code true}. 부르는 쪽이 ERROR 로 남긴다
   */
  @Transactional
  public boolean recordFailure(Long imageId) {
    Optional<Integer> attempts = chatImageRepository.findPendingExifAttempts(imageId);
    if (attempts.isEmpty()) {
      return false;
    }

    int next = attempts.get() + 1;
    if (next >= maxAttempts) {
      chatImageRepository.recordExifAttempt(imageId, next, ExifStatus.FAILED, null);
      return true;
    }

    chatImageRepository.recordExifAttempt(
        imageId, next, ExifStatus.PENDING, nowUtc().plus(backoffFor(attempts.get())));
    return false;
  }

  /**
   * 벗길 수 없는 파일이다. <b>재시도하지 않고</b> 바로 {@code FAILED} 로 굳힌다.
   *
   * <p>형식을 알 수 없거나 구조가 깨진 파일은 몇 번 다시 해도 결과가 같다. 재시도를 두면 같은 파일을 세 번 내려받는다.
   */
  @Transactional
  public void markUnsupported(Long imageId) {
    chatImageRepository
        .findPendingExifAttempts(imageId)
        .ifPresent(
            attempts ->
                chatImageRepository.recordExifAttempt(
                    imageId, attempts + 1, ExifStatus.FAILED, null));
  }

  private Duration backoffFor(int attemptsBeforeThisFailure) {
    return backoff.get(Math.min(attemptsBeforeThisFailure, backoff.size() - 1));
  }

  private LocalDateTime nowUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
