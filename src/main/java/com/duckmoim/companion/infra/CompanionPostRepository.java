package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CompanionPost;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * <b>getOrThrow 를 두지 않는다.</b> {@code PostErrorCode} 로 옮기는 것은 service 의 일이고, 저장소가 도메인 에러 코드를 던지면 조회
 * 경로마다 판정이 흩어진다.
 *
 * <p>목록·상세 조회(PO-08 · PO-11)는 방장과 행사 외부 식별자를 붙여 읽어야 해서 파생 쿼리 메서드로 감당되지 않는다. {@link
 * CompanionPostQueryRepository} 로 빼고 여기서 함께 상속한다 — service 에는 저장소 하나만 주입된다.
 */
public interface CompanionPostRepository
    extends JpaRepository<CompanionPost, Long>, CompanionPostQueryRepository {

  /**
   * 만남시각이 지난 모집중 글을 잠그고 읽는다 (PO-14).
   *
   * <p><b>엔티티로 읽는다.</b> 벌크 {@code UPDATE} 한 줄로 끝낼 수 있는 자리인데 도메인-모델링.md 「3.1 경계와 트랜잭션 범위」가 <i>"배치가
   * 닫을 때도 방장이 마감할 때도 같은 전이 규칙을 지나며, 배치용 별도 경로를 만들지 않는다"</i> 고 정했다. 상태와 사유를 함께 옮기는 보장이 {@code
   * CompanionPost} 안에만 있어서, {@code UPDATE} 문은 그 보장 밖이다.
   *
   * <p><b>{@code FOR UPDATE SKIP LOCKED} 다.</b> PO-14 의 검증 기준이 「다중 인스턴스에서 1회만 실행」이고, 잠긴 행을 건너뛰면 두
   * 인스턴스가 같은 글을 집지 않는다. {@code SKIP LOCKED} 가 아니면 뒤에 온 쪽이 잠금을 기다린 뒤 「이미 닫혔다」로 전부 흘린다 — 결과는 같지만
   * (도메인이 멱등이라) 그 시간이 통째로 낭비다.
   *
   * <p>힌트 값 {@code -2} 가 Hibernate 의 {@code SKIP_LOCKED} 다. 상수 이름이 아니라 숫자로 넣어야 하는 것은 애노테이션 속성이
   * {@code String} 이기 때문이다.
   *
   * <p><b>인덱스를 새로 내지 않았다.</b> {@code V22} 의 {@code ix_companion_post_status_meet_at_id} 가 선두를
   * 등식({@code status}), 다음을 범위({@code meet_at})로 두고 정렬 키까지 같아서 이 쿼리를 그대로 받는다. PO-08 목록을 위해 낸 인덱스인데
   * 이 쿼리가 그 목록의 부분집합 모양이다.
   *
   * @param nowInUtc UTC 기준 현재 시각. {@code meet_at} 이 UTC 로 저장된다 (도메인-모델링.md 「4. 엔티티 · 값 객체 · 식별자」)
   * @param chunk 한 번에 집을 건수. 밀린 글 전부를 한 트랜잭션에 담지 않기 위한 것이다
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "SELECT p FROM CompanionPost p"
          + " WHERE p.status = com.duckmoim.companion.domain.PostStatus.OPEN"
          + " AND p.meetAt < :nowInUtc"
          + " ORDER BY p.meetAt ASC, p.id ASC")
  List<CompanionPost> findOpenPostsWithMeetTimePassed(
      @Param("nowInUtc") LocalDateTime nowInUtc, Pageable chunk);
}
