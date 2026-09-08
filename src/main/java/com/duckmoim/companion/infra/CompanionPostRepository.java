package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CompanionPost;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>getOrThrow 를 두지 않는다.</b> {@code PostErrorCode} 로 옮기는 것은 service 의 일이고, 저장소가 도메인 에러 코드를 던지면 조회
 * 경로마다 판정이 흩어진다.
 *
 * <p>목록·상세 조회(PO-08 · PO-11)는 방장과 행사 외부 식별자를 붙여 읽어야 해서 파생 쿼리 메서드로 감당되지 않는다. {@link
 * CompanionPostQueryRepository} 로 빼고 여기서 함께 상속한다 — service 에는 저장소 하나만 주입된다.
 */
public interface CompanionPostRepository
    extends JpaRepository<CompanionPost, Long>, CompanionPostQueryRepository {}
