package com.duckmoim.identity.infra;

import com.duckmoim.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>조회 메서드를 늘리지 않는다.</b> 늘리는 순간 Identity 담당의 파일을 Comment 담당이 편집한 것이 되고, 양쪽이 같은 파일을 고쳐 충돌한다. 필요한
 * 조회가 생기면 PR 에서 합의한다 (아키텍처 컨벤션 · 의존성 방향 「절차」).
 */
public interface UserRepository extends JpaRepository<User, Long> {}
