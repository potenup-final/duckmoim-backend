package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.chat.service.ClaimedChatImages.ClaimedChatImage;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메시지에 실리지 않은 사진을 치우는 두 트랜잭션 (CH-17).
 *
 * <p>검증 기준이 <b>「확정되지 않은 객체가 주기 뒤 사라진다」</b>다. 주기와 S3 호출은 {@code ChatImageCleanupBatch} 가 지고, 여기는 DB
 * 쪽 두 걸음을 각자의 트랜잭션으로 진다 — {@code NotificationDispatchService} / {@code NotificationDispatchBatch} 와
 * 같은 배치다.
 *
 * <h4>순서가 이 클래스의 전부다 (PR #147 리뷰)</h4>
 *
 * <pre>
 * ① claimChunk     DELETING 으로 못박고 커밋       ← 여기서 이긴 행만 ② 로 간다
 * ② (배치)          S3 객체 삭제                    ← 트랜잭션 밖. 롤백이 안 되는 일
 * ③ removeClaimed  DELETING 인 행만 삭제
 * </pre>
 *
 * <p><b>처음에는 한 트랜잭션 안에서 읽고 → S3 를 지우고 → 행을 지웠다.</b> 읽은 엔티티의 상태를 다시 보는 가드({@code isOrphan})를 뒀지만 그것은
 * <b>메모리 스냅샷</b>이라 DB 를 다시 보지 않았고, 읽기와 삭제 사이에 커밋된 전송을 못 막았다.
 *
 * <pre>
 * 배치  SELECT 7 (CONFIRMED, 25h)
 * 전송                    attach → ATTACHED → COMMIT
 * 배치  isOrphan() → 스냅샷은 CONFIRMED → 통과
 * 배치  S3 삭제 ✗                    ← 되돌릴 수 없다
 * 배치  DELETE chat_image 7
 * 결과  image_id = 7 인 메시지 · 행도 객체도 없음
 * </pre>
 *
 * <p><b>{@code @Version} 만 더해서는 부족했다.</b> 행 삭제가 버전 충돌로 롤백돼 행은 살아남아도, 그 앞에서 지운 <b>S3 객체는 돌아오지
 * 않는다</b> — 사진이 붙은 채로 영원히 깨진다. 그래서 「내 것이다」를 먼저 커밋하고 나서야 객체를 지운다.
 *
 * <p><b>실패 방향이 전부 안전하다.</b>
 *
 * <pre>
 * ① 에서 전송이 이김      → 그 행은 못박히지 않는다. S3 는 건드리지 않는다
 * ① 뒤에 전송이 옴        → attach 가 버전 충돌로 400. 24시간 지난 고아라 맞는 답이다
 * ② 가 실패              → 행이 DELETING 으로 남아 다음 주기가 다시 집는다
 * ③ 전에 인스턴스가 죽음   → 같다. S3 의 DeleteObject 는 없는 키에도 성공한다
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ChatImageCleanupService {

  /**
   * 후보로 읽는 상태 셋.
   *
   * <p>{@code DELETING} 을 넣는 이유는 앞 주기에 S3 삭제가 실패한 행을 다시 집기 위해서다. {@code ATTACHED} 는 넣지 않는다 — 실려 있는
   * 사진을 지우면 말풍선이 없는 객체를 가리킨다.
   */
  private static final List<ChatImageStatus> CANDIDATE_STATUSES =
      List.of(ChatImageStatus.PENDING, ChatImageStatus.CONFIRMED, ChatImageStatus.DELETING);

  private final ChatImageRepository chatImageRepository;

  /**
   * 기준 시각보다 오래된 고아를 상한만큼 {@code DELETING} 으로 못박는다.
   *
   * <p><b>후보를 읽는 것과 가져가는 것이 다르다.</b> 후보 읽기는 잠그지 않고, 가져가는 것은 조건부 UPDATE 한 문장이 한다 — 그 문장이 쓰는 순간의 상태를
   * 보므로 읽은 뒤에 붙은 사진은 여기서 빠진다 ({@code ChatImageRepository#claimForDeletion}).
   *
   * <p><b>이 메서드가 끝나면 커밋된다.</b> 그 뒤에야 배치가 S3 를 부른다.
   *
   * @param thresholdInUtc 이 시각보다 먼저 만들어진 것만 집는다
   * @param limit 한 청크가 집을 최대 건수
   */
  @Transactional
  public ClaimedChatImages claimChunk(LocalDateTime thresholdInUtc, int limit) {
    List<Long> candidateIds =
        chatImageRepository
            .findByStatusInAndCreatedAtBeforeOrderByIdAsc(
                CANDIDATE_STATUSES, thresholdInUtc, Limit.of(limit))
            .stream()
            .map(ChatImage::getId)
            .toList();

    if (candidateIds.isEmpty()) {
      return new ClaimedChatImages(0, List.of());
    }

    chatImageRepository.claimForDeletion(candidateIds);

    List<ClaimedChatImage> claimed =
        chatImageRepository.findByIdInAndStatus(candidateIds, ChatImageStatus.DELETING).stream()
            .map(image -> new ClaimedChatImage(image.getId(), image.getObjectKey()))
            .toList();

    return new ClaimedChatImages(candidateIds.size(), claimed);
  }

  /**
   * S3 객체를 지운 뒤 행을 지운다.
   *
   * <p><b>{@code DELETING} 인 행만 지운다.</b> 다른 인스턴스의 배치가 먼저 지웠으면 0행이고 그것도 정상이다 — 이 배치는 잠그지 않고 멱등으로 둔다
   * (ADR 0009).
   *
   * @return 이번에 지웠으면 {@code true}
   */
  @Transactional
  public boolean removeClaimed(Long imageId) {
    return chatImageRepository.deleteClaimed(imageId) == 1;
  }
}
