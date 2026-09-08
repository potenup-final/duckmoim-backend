package com.duckmoim.auth.infra;

import com.duckmoim.auth.domain.RefreshToken;
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
   * <b>여기가 동시 재발급의 직렬화 지점이다.</b> 영향 행 수가 1인 요청만 회전을 진행하고, 0을 받은 쪽은 재사용으로 판정한다 (AU-03).
   *
   * <p><b>파생 쿼리로는 성립하지 않는다.</b> 이름으로 만들어지는 {@code deleteByTokenHash} 는 SELECT 로 엔티티를 읽고 하나씩 {@code
   * remove} 하므로, 같은 토큰으로 동시에 두 요청이 들어오면 둘 다 존재 검사를 지나고 <b>진 쪽의 DELETE 가 0행이 되어 {@code
   * StaleStateException} → 500</b> 이 난다. 재사용 탐지가 아예 돌지 못한다. 벌크 DELETE 는 JDBC 의 실제 영향 행 수를 돌려주고
   * InnoDB 행 잠금이 순서를 세운다.
   *
   * <p>{@code user_id} 를 조건에 함께 넣는다. 해시가 유니크라 사실상 같은 행이지만, 남의 행을 지울 수 있는 경로를 문법으로 막는다.
   *
   * <p><b>부르는 쪽에 트랜잭션이 있어야 한다</b> — service 의 {@code @Transactional} 안에서만 부른다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "delete from RefreshToken token"
          + " where token.tokenHash = :tokenHash and token.userId = :userId")
  int deleteByTokenHashAndUserId(
      @Param("tokenHash") String tokenHash, @Param("userId") Long userId);

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
