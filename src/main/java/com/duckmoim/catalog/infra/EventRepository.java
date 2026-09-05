package com.duckmoim.catalog.infra;

import com.duckmoim.catalog.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 행사 저장소.
 *
 * <p>{@link EventQueryRepository} 를 함께 상속해 동적 조회까지 이 인터페이스 하나로 노출한다. service 가 저장소 둘을 주입받지 않게 하려는
 * 것이다 (아키텍처 컨벤션 · 저장소).
 */
public interface EventRepository extends JpaRepository<Event, Long>, EventQueryRepository {}
