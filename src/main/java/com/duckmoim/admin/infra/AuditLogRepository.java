package com.duckmoim.admin.infra;

import com.duckmoim.admin.domain.AuditLog;
import org.springframework.data.repository.Repository;

/**
 * 감사 로그의 저장소 (AD-05 · I-13).
 *
 * <p><b>{@code JpaRepository} 를 상속하지 않는다.</b> 상속하면 {@code delete} · {@code deleteAll} · {@code
 * deleteById} 가 그대로 열린다. I-13 의 검증 위치가 「append-only <b>경로만</b> 제공」이라, 부르지 않는 것으로 지키는 규칙이 아니라 <b>부를
 * 수 있는 메서드가 없어야</b> 지켜지는 규칙이다.
 *
 * <p>그래서 표지 인터페이스 {@link Repository} 를 상속하고 필요한 것만 손으로 선언한다 — 여는 문은 {@link #save} 하나와 {@link
 * AuditLogQueryRepository} 의 조회 하나다.
 *
 * <p><b>{@code save} 가 수정 경로가 되지 않는 이유.</b> Spring Data 의 {@code save} 는 식별자가 붙은 엔티티를 주면 병합이지만,
 * {@link AuditLog} 에 수정자가 없어 바뀐 상태를 만들어 넘길 방법이 없다. 두 겹이 같은 방향으로 막고 있다.
 *
 * <p>이 저장소를 부르는 문은 {@code AuditLogRecorder} 하나여야 한다. AD-04 · AD-07 · CM-17 이 각자 저장소를 주입받으면 기록의 모양이
 * 셋으로 갈린다.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long>, AuditLogQueryRepository {

  /** 기록을 덧붙인다. 이 저장소가 여는 유일한 쓰기다. */
  AuditLog save(AuditLog auditLog);
}
