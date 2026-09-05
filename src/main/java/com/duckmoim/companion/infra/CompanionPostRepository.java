package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CompanionPost;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>조회 메서드도 getOrThrow 도 두지 않는다.</b> getOrThrow 가 던질 {@code PostErrorCode} 는 Companion 담당의 파일이고,
 * 그것을 여기서 만들면 양쪽이 같은 파일을 고쳐 충돌한다. 그때까지 Comment 는 {@code findById} 로 받아 자기 서비스에서 처리한다.
 */
public interface CompanionPostRepository extends JpaRepository<CompanionPost, Long> {}
