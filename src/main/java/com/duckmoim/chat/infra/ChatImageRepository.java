package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import com.duckmoim.chat.domain.ExifStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** 채팅 이미지 저장소 (CH-14 · CH-17). */
public interface ChatImageRepository extends JpaRepository<ChatImage, Long> {

  /**
   * 정리 후보 — 오래됐고 메시지에 실리지 않은 것들 (CH-17).
   *
   * <p><b>상태를 {@code NOT IN} 으로 쓰지 않고 목록으로 받는다.</b> 부르는 쪽이 집을 상태를 명시하고, 새 상태가 생길 때 이 질의가 조용히 그것을
   * 포함하지 않는다 — {@code ATTACHED} 말고 다 지우는 질의였다면 나중에 생길 「EXIF 처리 중」 상태(CH-16)가 지워졌을 것이다.
   *
   * <p><b>후보일 뿐이다.</b> 이 읽기는 잠그지 않으므로 반환 직후에 전송이 사진을 붙일 수 있다. 실제로 가져가는 것은 {@link #claimForDeletion}
   * 이다.
   *
   * <p><b>오래된 것부터 준다.</b> 한 주기가 상한만큼만 집으므로, 순서가 없으면 같은 묶음을 반복해 집고 옛것이 남는다. 인덱스는 {@code
   * idx_chat_image_orphan (status, created_at)} 이다.
   */
  List<ChatImage> findByStatusInAndCreatedAtBeforeOrderByIdAsc(
      List<ChatImageStatus> statuses, LocalDateTime threshold, Limit limit);

  /**
   * 후보 중 아직 고아인 것만 {@code DELETING} 으로 못박는다 (PR #147 리뷰).
   *
   * <p><b>읽기와 쓰기가 한 문장이다.</b> {@code WHERE status IN (PENDING, CONFIRMED)} 가 쓰는 순간의 현재 값을 보므로, 후보를
   * 읽은 뒤에 전송이 붙인 사진은 여기서 빠진다 — 메모리에 읽어 둔 상태로 판정하면 그 창이 열린다.
   *
   * <pre>
   * 전송이 먼저 커밋   → 이 UPDATE 는 ATTACHED 를 보고 건너뛴다
   * 전송이 행을 잠금 중 → 이 UPDATE 가 기다렸다가 ATTACHED 를 보고 건너뛴다
   * 이 UPDATE 가 먼저  → 전송의 attach 가 버전 충돌로 실패한다 ({@code ChatImage#version})
   * </pre>
   *
   * <p><b>버전을 함께 올린다.</b> 벌크 UPDATE 는 엔티티의 {@code @Version} 을 거치지 않아서, 안 올리면 이 행을 먼저 읽어 둔 전송이 버전
   * 검사를 통과해 {@code DELETING} 을 덮는다.
   *
   * @return 실제로 못박은 행 수
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE ChatImage i
         SET i.status = com.duckmoim.chat.domain.ChatImageStatus.DELETING,
             i.version = i.version + 1
       WHERE i.id IN :ids
         AND i.status IN (com.duckmoim.chat.domain.ChatImageStatus.PENDING,
                          com.duckmoim.chat.domain.ChatImageStatus.CONFIRMED)
      """)
  int claimForDeletion(@Param("ids") Collection<Long> ids);

  /**
   * 못박힌 행만 준다. S3 에서 지울 대상이다.
   *
   * <p>앞 주기에 S3 삭제가 실패해 {@code DELETING} 으로 남은 행도 함께 온다 — 그래서 후보 질의가 {@code DELETING} 까지 집는다.
   */
  List<ChatImage> findByIdInAndStatus(Collection<Long> ids, ChatImageStatus status);

  /**
   * S3 객체를 지운 뒤 행을 지운다.
   *
   * <p><b>{@code DELETING} 인 행만 지운다.</b> 조건 없이 id 로 지우면, 이론상 불가능하더라도 상태가 바뀐 행을 지울 여지가 남는다 — 이 표에는
   * FK 가 없어 그 실수를 DB 가 막아 주지 않는다.
   *
   * @return 지운 행 수. 다른 인스턴스의 배치가 먼저 지웠으면 0 이다
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      DELETE FROM ChatImage i
       WHERE i.id = :id
         AND i.status = com.duckmoim.chat.domain.ChatImageStatus.DELETING
      """)
  int deleteClaimed(@Param("id") Long id);

  // ── EXIF 워커 (CH-16) ────────────────────────────────────────────────────
  //
  // ⚠️ 아래는 전부 버전을 올리지 않는다. 워커는 확정 직후, 사용자가 보내는 바로 그 몇 초
  // 사이에 돈다 — 버전을 올리면 전송의 attach 가 버전 충돌로 400 을 맞는다. 워커가 쓰는 열
  // (exif_*)과 전송·정리가 쓰는 열이 겹치지 않고, ChatImage 가 @DynamicUpdate 라 서로 덮지
  // 않는다.

  /**
   * EXIF 를 벗길 사진을 잠그고 읽는다 (CH-16 · ADR 0008).
   *
   * <p><b>{@code FOR UPDATE SKIP LOCKED} 다.</b> 인스턴스가 둘이라 워커도 둘이고, 사진 한 장이 내려받기 · 재작성 · 업로드라 헛일 비용이
   * 알림보다 훨씬 크다. 남이 잠근 행은 건너뛴다.
   *
   * <p><b>수명주기가 {@code CONFIRMED} · {@code ATTACHED} 인 것만 집는다.</b> {@code PENDING} 은 저장소에 올라왔는지조차
   * 모르고, {@code DELETING} 은 고아 정리가 지우는 중이다.
   *
   * <p><b>{@code PESSIMISTIC_WRITE} 는 버전을 올리지 않는다</b> ({@code PESSIMISTIC_FORCE_INCREMENT} 와 다르다).
   * 잠그기만 한다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      SELECT i FROM ChatImage i
       WHERE i.exifStatus = com.duckmoim.chat.domain.ExifStatus.PENDING
         AND i.status IN (com.duckmoim.chat.domain.ChatImageStatus.CONFIRMED,
                          com.duckmoim.chat.domain.ChatImageStatus.ATTACHED)
         AND (i.exifNextAttemptAt IS NULL OR i.exifNextAttemptAt <= :nowInUtc)
       ORDER BY i.id ASC
      """)
  List<ChatImage> findExifProcessableForUpdate(
      @Param("nowInUtc") LocalDateTime nowInUtc, Pageable pageable);

  /**
   * 집은 행에 리스를 적는다. 잠금은 트랜잭션이 끝나면 풀리지만 처리는 그 뒤에 일어나므로, 풀린 뒤에도 남는 표시가 필요하다 (ADR 0008).
   *
   * <p>리스는 시각이라 워커가 죽어도 저절로 풀린다 — 「처리 중」 상태를 두면 거기서 빼내는 장치를 따로 만들어야 한다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE ChatImage i
         SET i.exifNextAttemptAt = :leaseUntil
       WHERE i.id IN :ids
         AND i.exifStatus = com.duckmoim.chat.domain.ExifStatus.PENDING
      """)
  int leaseExif(@Param("ids") Collection<Long> ids, @Param("leaseUntil") LocalDateTime leaseUntil);

  /**
   * 벗겼다고 적는다.
   *
   * <p><b>그 사이 고아 정리가 못박았으면 적지 않는다</b> — 수명주기 조건이 그것을 거른다. 지워질 행에 {@code STRIPPED} 가 적혀도 해는 없지만, 행
   * 수로 「이번에 적었나」를 판정하려면 조건이 정확해야 한다.
   *
   * @return 적었으면 1
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE ChatImage i
         SET i.exifStatus = com.duckmoim.chat.domain.ExifStatus.STRIPPED,
             i.exifNextAttemptAt = NULL
       WHERE i.id = :id
         AND i.exifStatus = com.duckmoim.chat.domain.ExifStatus.PENDING
         AND i.status IN (com.duckmoim.chat.domain.ChatImageStatus.CONFIRMED,
                          com.duckmoim.chat.domain.ChatImageStatus.ATTACHED)
      """)
  int markExifStripped(@Param("id") Long id);

  /** 아직 벗기지 않은 행의 실패 횟수. 백오프 간격을 고르는 입력이다. */
  @Query(
      """
      SELECT i.exifAttempts FROM ChatImage i
       WHERE i.id = :id
         AND i.exifStatus = com.duckmoim.chat.domain.ExifStatus.PENDING
      """)
  Optional<Integer> findPendingExifAttempts(@Param("id") Long id);

  /**
   * 실패를 적는다. 재시도가 남았으면 {@code PENDING} 그대로 다음 시각을, 다 썼으면 {@code FAILED} 를 적는다.
   *
   * @return 적었으면 1
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE ChatImage i
         SET i.exifAttempts = :attempts,
             i.exifStatus = :exifStatus,
             i.exifNextAttemptAt = :nextAttemptAt
       WHERE i.id = :id
         AND i.exifStatus = com.duckmoim.chat.domain.ExifStatus.PENDING
      """)
  int recordExifAttempt(
      @Param("id") Long id,
      @Param("attempts") int attempts,
      @Param("exifStatus") ExifStatus exifStatus,
      @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
