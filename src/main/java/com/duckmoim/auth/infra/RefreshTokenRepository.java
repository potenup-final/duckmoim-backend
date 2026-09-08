package com.duckmoim.auth.infra;

import com.duckmoim.auth.domain.RefreshToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  /**
   * 원문으로는 찾을 수 없다. {@code RefreshToken.hash(원문)} 을 지나 온 해시로만 조회한다.
   *
   * <p><b>빈 값이 곧 재사용이다</b> — 회전할 때 헌 행을 지우므로, 서명은 멀쩡한데 행이 없다는 것은 누가 이미 썼다는 뜻이다 (AU-03). 지우는 것은
   * {@code delete(엔티티)} 로 한다. 만료와 소유자를 보려고 어차피 엔티티를 읽어둔 뒤다.
   */
  Optional<RefreshToken> findByTokenHash(String tokenHash);

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
