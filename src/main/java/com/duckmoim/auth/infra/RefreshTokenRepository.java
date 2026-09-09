package com.duckmoim.auth.infra;

import com.duckmoim.auth.domain.RefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  /**
   * 원문으로는 찾을 수 없다. {@code RefreshToken.hash(원문)} 을 지나 온 해시로만 조회한다.
   *
   * <p><b>빈 값이 곧 재사용이다</b> — 회전할 때 헌 행을 지우므로, 서명은 멀쩡한데 행이 없다는 것은 누가 이미 썼다는 뜻이다 (AU-03). 지우는 것은
   * {@code delete(엔티티)} 로 한다. 만료와 소유자를 보려고 어차피 엔티티를 읽어둔 뒤다.
   */
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * <b>여기가 동시 재발급의 직렬화 지점이다.</b> 영향 행 수가 1인 요청만 회전을 진행한다 (AU-03).
   *
   * <p><b>지우지 않고 표시한다.</b> 지우면 「방금 회전됐다」와 「오래 전에 죽었다」를 구분할 수 없어, 탭 둘이 같은 Refresh 로 동시에 재발급할 때 진 쪽이
   * 이긴 쪽의 새 세션을 폐기해 버린다 — 실측했다. {@code rotated_at is null} 조건이 승자를 하나로 만든다.
   *
   * <p><b>단일 문장이어야 한다.</b> 「읽어서 있으면 지운다」로 짜면 동시 요청 둘이 모두 존재 검사를 통과한다. 파생 쿼리도 조회 후 건건이 지우는 방식이라 안
   * 되고, {@code @Modifying @Query} 여야 JDBC 의 실제 영향 행 수가 나온다.
   *
   * <p>{@code user_id} 를 조건에 함께 넣어 남의 행을 건드릴 경로를 문법으로 막는다.
   *
   * <p><b>부르는 쪽에 트랜잭션이 있어야 한다</b> — service 의 {@code @Transactional} 안에서만 부른다.
   */
  @Modifying
  @Query(
      "update RefreshToken token set token.rotatedAt = :now"
          + " where token.tokenHash = :tokenHash"
          + " and token.userId = :userId"
          + " and token.rotatedAt is null")
  int markRotated(
      @Param("tokenHash") String tokenHash,
      @Param("userId") Long userId,
      @Param("now") LocalDateTime now);

  /**
   * 그 토큰이 <b>유예 안에 회전됐는지</b> 센다 — 1이면 재사용이 아니라 이중 제출이다 (AU-03).
   *
   * <p>부르는 쪽이 이미 회원 행을 잠갔고, 그 잠금은 이긴 쪽이 커밋한 뒤에야 얻어진다. 그래서 이 읽기는 <b>이긴 쪽이 찍은 {@code rotated_at} 을
   * 반드시 본다</b> — REPEATABLE READ 의 스냅숏이 언제 만들어지든 그 시점이 커밋 뒤다.
   */
  @Query(
      "select count(token) from RefreshToken token"
          + " where token.tokenHash = :tokenHash"
          + " and token.userId = :userId"
          + " and token.rotatedAt >= :graceFrom")
  long countRotatedSince(
      @Param("tokenHash") String tokenHash,
      @Param("userId") Long userId,
      @Param("graceFrom") LocalDateTime graceFrom);

  /**
   * 그 회원의 <b>쓸모를 다한 행</b>을 지운다 — 유예를 넘겨 회전된 것과 만료된 것.
   *
   * <p>회전이 행을 남기게 됐으므로 누군가는 치워야 한다. <b>다음 회전이 함께 치운다</b> — 30분마다 오는 요청에 얹으면 별도 배치도, 스케줄러도 필요 없고,
   * 활동하는 회원의 행은 항상 두어 개로 유지된다. 회원 행을 잠근 트랜잭션 안에서 돌아 경합도 없다.
   *
   * <p>돌아오지 않는 회원의 만료 행은 남는다. 그것은 정리 배치의 몫으로 남겨 둔다 (계획서 「남겨둔 것」).
   */
  @Modifying
  @Query(
      "delete from RefreshToken token"
          + " where token.userId = :userId"
          + " and (token.rotatedAt < :graceFrom or token.expiresAt <= :now)")
  int deleteStale(
      @Param("userId") Long userId,
      @Param("graceFrom") LocalDateTime graceFrom,
      @Param("now") LocalDateTime now);

  /**
   * 그 회원의 토큰을 전부 지운다 — 재사용 탐지의 「해당 유저 전체 폐기」(AU-03)와 로그아웃(AU-04)이 함께 쓴다.
   *
   * <p>이것만으로는 이미 발급된 Access 를 막지 못한다. {@code user.tokensInvalidatedAt} 갱신이 같은 트랜잭션에서 따라온다.
   *
   * <p><b>부르는 쪽에 트랜잭션이 있어야 한다.</b> 파생 삭제는 엔티티를 읽어 하나씩 {@code remove} 하므로 없으면 {@code
   * InvalidDataAccessApiUsageException} 이 난다 — 실측했다. service 의 {@code @Transactional} 안에서만 부른다.
   */
  void deleteAllByUserId(Long userId);
}
