package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.Notification;
import com.duckmoim.notification.domain.NotificationListQuery;
import java.util.List;

/**
 * 알림함의 조회 (NT-08).
 *
 * <p>커서 조건이 선택이라 파생 쿼리 메서드로는 감당할 수 없다. 커스텀 프래그먼트로 두고 {@link NotificationRepository} 가 함께 상속한다 —
 * service 에는 여전히 저장소 하나만 주입된다.
 *
 * <p><b>투영 레코드를 두지 않는다.</b> 다른 목록들은 조인한 값을 함께 실어 나르지만 (내 댓글 내역의 모집글 제목 같은 것), 알림 응답은 {@code kind} 와
 * 참조 ID 만 내려주고 문구는 프론트가 조립한다 (API-설계.md 「2-10. 알림 (Notification) · 2차」). 조인할 것이 없어 엔티티가 그대로 한 항목이다.
 */
public interface NotificationQueryRepository {

  /**
   * 내 알림을 {@code (createdAt, id)} <b>내림차순</b>으로 {@code size + 1} 건까지 읽는다.
   *
   * <p>한 건을 더 읽는 것은 다음 페이지 유무를 알기 위해서다. 별도 count 쿼리를 돌리지 않는다 — 커서 페이지네이션 응답에 총 건수가 없다 (API-컨벤션.md
   * 「공통 응답 형식」).
   */
  List<Notification> findSlice(NotificationListQuery query);
}
