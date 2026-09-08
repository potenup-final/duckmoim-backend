package com.duckmoim.admin.service;

import com.duckmoim.admin.domain.AuditLog;
import com.duckmoim.admin.domain.AuditLogCursor;
import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.infra.ActedAuditLog;
import com.duckmoim.admin.infra.AuditLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 조회 (AD-05).
 *
 * <p><b>가시성 판정이 없다.</b> 이 경로에 닿는 사람은 이미 관리자다 — 인가가 {@code /api/v1/admin/**} 전체에 인터셉터 한 곳으로 걸려 있다
 * (D-5 의 지켜야 할 셋 중 둘째). 댓글 조회가 요청자마다 본문 노출을 갈랐던 것과 갈리는 지점이다.
 *
 * <p><b>기록기와 나눠 둔다.</b> 한 클래스에 담으면 조회하러 주입받은 곳에서 기록도 부를 수 있게 된다. 기록은 관리자 행위의 부수 효과이고 조회는 백오피스 화면의
 * 요청이라 부르는 쪽도 시점도 다르다.
 */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

  private final AuditLogRepository auditLogRepository;

  /** 감사 로그를 한 페이지 읽는다. 거를 것이 없어 읽은 것이 그대로 한 페이지다. */
  @Transactional(readOnly = true)
  public AuditLogSlice findAuditLogs(AuditLogListQuery query) {
    List<ActedAuditLog> read = auditLogRepository.findSlice(query);

    boolean hasNext = read.size() > query.size();
    List<ActedAuditLog> page = hasNext ? read.subList(0, query.size()) : read;

    return new AuditLogSlice(views(page), nextCursor(page, hasNext), hasNext);
  }

  private static List<AuditLogView> views(List<ActedAuditLog> page) {
    return page.stream().map(AuditLogQueryService::view).toList();
  }

  private static AuditLogView view(ActedAuditLog acted) {
    AuditLog log = acted.auditLog();

    return new AuditLogView(
        log.getId(),
        log.getAt(),
        acted.actorNickname(),
        log.getKind(),
        log.getTargetType(),
        log.getTargetId(),
        log.getDetail());
  }

  /** 다음 페이지의 시작점. 이 페이지의 마지막 기록을 가리킨다. */
  private static AuditLogCursor nextCursor(List<ActedAuditLog> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    AuditLog last = page.get(page.size() - 1).auditLog();
    return new AuditLogCursor(last.getAt(), last.getId());
  }
}
