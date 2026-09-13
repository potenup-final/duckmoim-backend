package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImageStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
}
