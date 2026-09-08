package com.duckmoim.safety.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.Report;
import com.duckmoim.safety.exception.ReportErrorCode;
import com.duckmoim.safety.infra.ReportRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백오피스가 신고를 처리한다 (AD-03).
 *
 * <p><b>감사 로그를 부르지 않는다.</b> 감사 로그가 남기는 다섯에 신고 처리가 없다 — AD-03 의 「이력 기록」을 {@code Report} 애그리게이트가 이미
 * 지고 있어서, 같은 사실을 두 곳에 두면 갈라진다 (도메인-모델링.md 「1. 유비쿼터스 언어」 각주). 이 경로가 백오피스인데도 {@code AuditLogRecorder}
 * 를 주입받지 않는 이유다.
 *
 * <p><b>조치를 여기서 하지 않는다.</b> 제재(AD-04)와 블라인드(AD-07)는 각자 엔드포인트를 갖고 있고, 하나의 신고에서 둘이 함께 나올 수도 아무것도 안 나올
 * 수도 있다. 여기서 함께 실행하면 조합이 이 서비스로 밀려 들어온다 — 관리자가 조치를 먼저 하고 이 경로로 종결을 적는다.
 */
@Service
@RequiredArgsConstructor
public class ReportHandleService {

  private final ReportRepository reportRepository;
  private final Clock clock;

  /**
   * 상태를 옮기고 이력을 남긴다.
   *
   * <p>전이 규칙과 거부는 도메인이 판정한다 ({@code Report.handle}). 여기서는 읽고 시각을 주는 일만 한다.
   *
   * <p><b>돌려주는 것이 없다.</b> 목록 응답({@code ReportView})을 여기서 만들려면 대상 표시명과 신고자 닉네임을 다시 조인해야 하는데, 명령 경로가
   * 조회 쿼리를 한 번 더 도는 셈이다. 처리하고 나면 그 건이 상태 필터 밖으로 나가 화면이 어차피 목록을 다시 읽는다. {@code deleteComment} ·
   * {@code blindComment} 와 같은 판단이다.
   */
  @Transactional
  public void handle(ReportHandleCommand command) {
    Report report =
        reportRepository
            .findById(command.reportId())
            .orElseThrow(() -> new BusinessException(ReportErrorCode.REPORT_NOT_FOUND));

    report.handle(
        command.status(), command.result(), command.memo(), command.adminUserId(), nowInUtc());
  }

  /**
   * 저장은 UTC 다 (도메인-모델링.md 4장). 주입된 시계는 KST 라 그대로 {@code LocalDateTime.now(clock)} 을 부르면 아홉 시간 앞선 값이
   * 들어간다.
   */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
