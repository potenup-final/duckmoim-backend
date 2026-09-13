package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.StoredChatImage;
import com.duckmoim.chat.infra.exif.ImageMetadataStripper;
import com.duckmoim.chat.infra.exif.UnsupportedImageException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 올라온 사진에서 좌표를 벗긴다 (CH-16).
 *
 * <p>명세: <i>"사진에 촬영 좌표가 들어 있다. 개인위치정보를 수집하지 않기로 한 판단을 뒷문으로 되돌리지 않는다. 업로드 뒤 비동기 워커가 벗긴다"</i>.
 *
 * <p><b>왜 워커인가.</b> CH-14 가 바이트를 서버에 안 거치게 만들었다 (브라우저 → S3 직접 PUT). 올리는 순간에 벗길 기회가 없어 올라간 뒤에 꺼내서
 * 벗긴다. 그 일을 확정 요청 안에서 하면 사용자가 내려받기 · 재작성 · 업로드를 기다린다.
 *
 * <pre>
 * ⏰ 10초마다
 *   claimProcessableIds   잠그고 읽고 리스         (트랜잭션)
 *   findTarget            아직 대상인가            (읽기)
 *   download              S3 에서 받기            ┐
 *   strip                 메타데이터 벗기기         ├ 트랜잭션 밖
 *   overwriteIfUnchanged  If-Match 로 덮어쓰기     ┘
 *   markStripped          벗겼다                  (트랜잭션)
 * </pre>
 *
 * <p><b>전송은 이 워커를 기다리지 않는다.</b> 대신 보여주는 쪽(CH-15)이 {@code ChatImage#isExifStripped} 가 참일 때만 서명을 발급한다
 * — 그 계약이 이 워커와 CH-15 의 경계다.
 *
 * <p><b>{@code @Scheduled} 를 service 에 둔다</b> ({@code NotificationDispatchBatch} 와 같은 판단).
 * presentation 은 HTTP 관심사로 닫혀 있고, 네 레이어 밖에 패키지를 만들면 {@code LAYER_DEPENDENCY} 가 그 클래스를 검사하지 않는다.
 */
@Slf4j
@Service
public class ChatImageExifWorker {

  /**
   * 한 주기가 돌릴 최대 청크 수.
   *
   * <p>없어도 끝나야 한다 — 처리한 행은 {@code STRIPPED} · {@code FAILED} 가 되거나 리스로 미래로 밀려 다음 조회에서 빠진다. <b>그럼에도
   * 두는 것은 그 전제가 깨진 날을 위해서다.</b> 조건과 결과가 어긋나면 같은 행을 계속 집는데, 그때 상한이 없으면 워커 스레드가 영구히 물린다.
   */
  private static final int MAX_CHUNKS = 20;

  private final ChatImageExifService chatImageExifService;
  private final ChatImageStorage storage;
  private final ImageMetadataStripper stripper;
  private final ChatImageJobExecutor jobExecutor;
  private final Clock clock;
  private final int chunk;

  /** 앞 회차가 아직 도는가. 전용 스레드로 넘기면 {@code @Scheduled} 의 「겹치지 않음」 보장이 사라져 직접 든다. */
  private final AtomicBoolean running = new AtomicBoolean();

  public ChatImageExifWorker(
      ChatImageExifService chatImageExifService,
      ChatImageStorage storage,
      ImageMetadataStripper stripper,
      ChatImageJobExecutor jobExecutor,
      Clock clock,
      @Value("${duckmoim.chat.image.exif.chunk}") int chunk) {

    this.chatImageExifService = chatImageExifService;
    this.storage = storage;
    this.stripper = stripper;
    this.jobExecutor = jobExecutor;
    this.clock = clock;
    this.chunk = chunk;
  }

  /**
   * 벗길 사진이 없어질 때까지 청크를 돌린다.
   *
   * <p><b>예외를 밖으로 던지지 않는다.</b> 스케줄러에서 예외가 올라가면 다음 실행이 오는지가 설정에 달린다. 여기서 잡으면 다음 주기가 곧 재시도다.
   *
   * <p><b>0건이면 로그를 남기지 않는다.</b> 10초 주기라 대부분의 실행이 0건이다 — 알림 워커와 같은 판단이다.
   */
  @Scheduled(cron = "${duckmoim.chat.image.exif.cron}")
  public void scheduleStripping() {
    jobExecutor.submitIfIdle(running, this::stripPendingImages);
  }

  /**
   * 한 회차를 이 스레드에서 끝까지 돈다. 스케줄러는 {@link #scheduleStripping} 으로 전용 스레드에 넘기고, 검사는 이것을 바로 부른다.
   *
   * <p><b>스케줄러 스레드에서 부르지 않는다</b> (리뷰). 한 회차가 최대 200장 × (10MB 내려받기 + 업로드)라 몇 분이 걸릴 수 있고, 그동안 배치
   * 스케줄러(스레드 2)의 한 자리를 차지하면 SSE 하트비트가 발화하지 못한다 ({@code ChatImageJobExecutor}).
   */
  public void stripPendingImages() {
    try {
      int stripped = processUntilDrained(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

      if (stripped > 0) {
        log.info("[ChatImageExifWorker.stripPendingImages] EXIF 를 벗겼다. count={}", stripped);
      }
    } catch (Exception exception) {
      log.error("[ChatImageExifWorker.stripPendingImages] EXIF 워커가 실패했다.", exception);
    }
  }

  private int processUntilDrained(LocalDateTime nowInUtc) {
    int stripped = 0;

    for (int chunks = 0; chunks < MAX_CHUNKS; chunks++) {
      List<Long> ids = chatImageExifService.claimProcessableIds(nowInUtc, chunk);

      for (Long imageId : ids) {
        if (processOne(imageId)) {
          stripped++;
        }
      }

      if (ids.size() < chunk) {
        return stripped;
      }
    }

    log.warn("[ChatImageExifWorker.processUntilDrained] 청크 상한에 닿았다. stripped={}", stripped);
    return stripped;
  }

  /**
   * 한 장을 처리한다. 실패를 두 갈래로 가른다.
   *
   * <pre>
   * UnsupportedImageException   형식을 모르거나 구조가 깨졌다   → 재시도 없이 FAILED
   * 그 밖의 예외                 네트워크 · 권한 · 일시 장애      → 백오프 뒤 재시도, 다 쓰면 FAILED
   * </pre>
   *
   * <p><b>결과가 입력과 같으면 덮어쓰지 않는다.</b> 이미 벗긴 파일을 다시 받은 경우다 (리스가 풀린 뒤의 재처리 · 기존 사진 일괄 처리). 벗기기가 멱등이라
   * 바이트 비교로 판정할 수 있다.
   *
   * <p><b>덮어쓰기 조건이 어긋나면 조용히 넘어간다.</b> 그 사이 고아 정리가 지웠거나 다른 워커가 먼저 썼다 — 앞쪽은 행도 곧 사라지고, 뒤쪽은 그 워커가
   * {@code STRIPPED} 를 적는다.
   *
   * @return 이번에 {@code STRIPPED} 를 적었으면 {@code true}
   */
  private boolean processOne(Long imageId) {
    try {
      Optional<ExifTarget> target = chatImageExifService.findTarget(imageId);
      if (target.isEmpty()) {
        return false;
      }

      String objectKey = target.get().objectKey();
      Optional<StoredChatImage> stored = storage.download(objectKey);
      if (stored.isEmpty()) {
        // 행은 벗길 대상인데 객체가 없다. 정리 중이 아니라면 이상 상태라 재시도를 세어 끝이 있게 한다.
        recordFailure(imageId, "객체가 없다");
        return false;
      }

      byte[] original = stored.get().bytes();
      byte[] stripped = stripper.strip(original);

      if (!Arrays.equals(stripped, original)
          && !storage.overwriteIfUnchanged(
              objectKey, stripped, stored.get().contentType(), stored.get().etag())) {
        return false;
      }

      return chatImageExifService.markStripped(imageId);

    } catch (UnsupportedImageException unsupported) {
      chatImageExifService.markUnsupported(imageId);
      log.error(
          "[ChatImageExifWorker.processOne] 벗길 수 없는 파일이라 영구히 보여주지 않는다. imageId={} reason={}",
          imageId,
          unsupported.getMessage());
      return false;

    } catch (Exception exception) {
      recordFailure(imageId, exception.getClass().getSimpleName());
      return false;
    }
  }

  private void recordFailure(Long imageId, String cause) {
    try {
      if (chatImageExifService.recordFailure(imageId)) {
        log.error(
            "[ChatImageExifWorker.recordFailure] 재시도를 다 써서 영구히 보여주지 않는다. imageId={} cause={}",
            imageId,
            cause);
      } else {
        log.warn(
            "[ChatImageExifWorker.recordFailure] EXIF 제거 실패 — 다시 시도한다. imageId={} cause={}",
            imageId,
            cause);
      }
    } catch (Exception exception) {
      log.error("[ChatImageExifWorker.recordFailure] 실패 기록에 실패했다. imageId={}", imageId, exception);
    }
  }
}
