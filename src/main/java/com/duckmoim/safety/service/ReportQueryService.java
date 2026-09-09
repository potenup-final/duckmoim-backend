package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.Report;
import com.duckmoim.safety.domain.ReportCursor;
import com.duckmoim.safety.domain.ReportListQuery;
import com.duckmoim.safety.infra.ReportRepository;
import com.duckmoim.safety.infra.ReportedTarget;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백오피스 신고 큐 조회 (AD-02).
 *
 * <p><b>가시성 판정이 없다.</b> 이 경로에 닿는 사람은 이미 관리자다 — 인가가 {@code /api/v1/admin/**} 전체에 관문 한 곳으로 걸려 있다 (D-5
 * 의 지켜야 할 셋 중 둘째).
 *
 * <p><b>목록에 댓글 본문을 싣지 않는다</b> (화면-계약.md). 미리 실으면 열람 시점을 기록할 수 없다 — 본문은 CM-17 의 전용 경로로만 보고 그 호출마다 감사
 * 로그가 남는다. {@code secret} 만 내려 화면이 「본문 보기」를 그리게 한다.
 *
 * <p><b>처리 서비스와 나눠 둔다.</b> 조회는 백오피스 화면의 요청이고 처리는 관리자의 행위라 부르는 쪽도 시점도 다르다. {@code
 * AuditLogQueryService} 가 기록기와 갈려 있는 것과 같은 판단이다.
 */
@Service
@RequiredArgsConstructor
public class ReportQueryService {

  private final ReportRepository reportRepository;

  /** 신고를 한 페이지 읽는다. 거를 것이 없어 읽은 것이 그대로 한 페이지다. */
  @Transactional(readOnly = true)
  public ReportSlice findReports(ReportListQuery query) {
    List<ReportedTarget> read = reportRepository.findSlice(query);

    boolean hasNext = read.size() > query.size();
    List<ReportedTarget> page = hasNext ? read.subList(0, query.size()) : read;

    return new ReportSlice(views(page), nextCursor(page, hasNext), hasNext);
  }

  private static List<ReportView> views(List<ReportedTarget> page) {
    return page.stream().map(ReportQueryService::view).toList();
  }

  /** {@code secret} 이 null 인 것은 대상이 댓글이 아니라는 뜻이다. 화면에는 false 로 내린다. */
  private static ReportView view(ReportedTarget target) {
    Report report = target.report();

    return new ReportView(
        report.getId(),
        report.getTargetType(),
        report.getTargetId(),
        target.subject(),
        report.getReason(),
        report.getDetail(),
        target.reporterNickname(),
        report.getCreatedAt(),
        report.getStatus(),
        report.getResult(),
        report.getMemo(),
        Boolean.TRUE.equals(target.secret()));
  }

  /** 다음 페이지의 시작점. 이 페이지의 마지막 신고를 가리킨다. */
  private static ReportCursor nextCursor(List<ReportedTarget> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    Report last = page.get(page.size() - 1).report();
    return new ReportCursor(last.getCreatedAt(), last.getId());
  }
}
