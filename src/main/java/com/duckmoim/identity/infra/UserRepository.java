package com.duckmoim.identity.infra;

import com.duckmoim.identity.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <b>조회 메서드를 늘리지 않는다.</b> 늘리는 순간 Identity 담당의 파일을 Comment 담당이 편집한 것이 되고, 양쪽이 같은 파일을 고쳐 충돌한다. 필요한
 * 조회가 생기면 PR 에서 합의한다 (아키텍처 컨벤션 · 의존성 방향 「절차」).
 */
public interface UserRepository extends JpaRepository<User, Long> {

  /**
   * 그 닉네임을 쓰는 회원이 있는지 (AU-06 사전 조회).
   *
   * <p><b>확정이 아니다.</b> 이 조회와 저장 사이에 남이 같은 닉네임을 넣을 수 있다 — API 설계 2-2 가 <i>"사전 조회. 확정은 아니다"</i> 로 적어
   * 둔 것이 그 뜻이다. 실제 유일성은 {@code uk_user_nickname} 이 지키고 위반을 409 로 옮긴다 (I-01 의 이중 방어).
   *
   * <p><b>탈퇴 회원을 걸러내지 않는다.</b> 탈퇴는 소프트 삭제라 행이 남고 유니크 제약도 그 행을 그대로 센다. 여기서만 걸러내면 「사용 가능」이라고 답한 닉네임이
   * 저장에서 409 가 되어, <b>사용자가 원인을 알 수 없는 실패</b>를 본다.
   */
  boolean existsByNickname(String nickname);

  /**
   * 회원 행을 잠그고 읽는다 ({@code SELECT ... FOR UPDATE}).
   *
   * <p><b>토큰 쓰기 경로의 락 순서를 하나로 만드는 장치다.</b> 발급 · 회전 · 폐기가 모두 {@code refresh_token} 과 {@code user} 둘을
   * 건드리는데, 어느 쪽을 먼저 잡느냐가 경로마다 달라 <b>같은 토큰으로 동시에 재발급하면 데드락</b>이 났다 — {@code
   * CannotAcquireLockException} 을 CI 에서 실측했다. 모든 경로가 이 회원 행을 <b>가장 먼저</b> 잠그면 한 회원의 토큰 작업이 완전히
   * 직렬화되고 데드락이 성립할 수 없다.
   *
   * <p>한 회원의 재발급이 30분에 한 번이라 직렬화 비용이 없다. 오히려 그 직렬화가 AU-03 이 요구한 판정이다.
   *
   * <p><b>{@code findById} 로는 안 된다.</b> 잠기지 않은 읽기라 두 트랜잭션이 같이 지나간다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select user from User user where user.id = :userId")
  Optional<User> findByIdForUpdate(@Param("userId") Long userId);
}
