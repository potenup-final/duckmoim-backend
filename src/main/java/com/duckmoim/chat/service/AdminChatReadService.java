package com.duckmoim.chat.service;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.service.AuditLogRecorder;
import com.duckmoim.chat.domain.MessageCursor;
import com.duckmoim.chat.domain.MessageListQuery;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.AuthoredMessage;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.Report;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.exception.ReportErrorCode;
import com.duckmoim.safety.infra.ReportRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고가 접수된 방의 대화를 관리자가 읽는다 (AD-08).
 *
 * <p><b>{@code AdminCommentReadService} 와 같은 모양이다</b> (CM-17). 읽고, 감사 로그를 남기고, 돌려준다. 1차가 비밀 댓글 본문에
 * 대해 잡아 둔 자리라 새로 설계할 것이 없다.
 *
 * <p><b>갈리는 것은 열람 자격이다.</b> 비밀 댓글은 관리자면 볼 수 있고 {@code reportId} 는 감사 로그를 풍부하게 하는 값이지만, 여기는 <b>넘긴
 * 신고가 자격 그 자체</b>다 (API-설계.md 「2-7. 백오피스 (Admin)」). 검증 기준이 「신고 없는 방 열람 시 403」 이다.
 *
 * <p><b>없는 신고는 404 다.</b> 검증 기준의 「신고 없는 방 열람 시 403」 은 <b>신고가 이 방을 안 가리키는 경우</b>를 말한다 — 없는 번호까지 403
 * 으로 뭉치면 관문의 403 과 구분되지 않는다.
 *
 * <p><b>「이 방을 가리키는 신고가 하나라도 있나」로 짜지 않았다.</b> {@code report} 의 유니크 키 선두가 {@code reporter_id} 라 그 질의는
 * 인덱스를 못 타고, 방 신고와 그 방의 메시지 신고를 둘 다 세야 해서 조인이 는다. 신고 번호를 받으면 PK 조회 하나로 끝난다.
 *
 * <p><b>Chat 이 Safety 를 참조한다.</b> 컨텍스트를 가로지르는 방향이고, Companion 이 쓰기 판정을 위해 Safety 를 참조하는 것과 같은 모양이다
 * (도메인-모델링.md 「2. 바운디드 컨텍스트」). 반대 방향이 아닌 이유는 <b>신고가 메시지의 소속 방을 알 필요가 없기</b> 때문이다.
 */
@Service
@RequiredArgsConstructor
public class AdminChatReadService {

  private final ChatMessageRepository chatMessageRepository;
  private final ReportRepository reportRepository;
  private final AuditLogRecorder auditLogRecorder;
  private final Clock clock;

  /**
   * 대화 한 페이지를 읽는다.
   *
   * <p><b>커서는 멤버 목록(CH-09)과 같은 것을 쓴다.</b> 관리자만 다른 페이징을 쓰면 같은 경계 버그를 두 번 고쳐야 한다.
   *
   * <p><b>지운 메시지의 본문도 준다.</b> 멤버에게는 자리표시자로 나가지만 ({@code ChatMessageQueryService}) 가린 뒤에도 판단 근거는 남아야
   * 한다 — 소프트 삭제로 본문을 남겨 둔 이유가 그것이다 (CM-14 가 댓글에 대해 정한 자리와 같다).
   *
   * @param reportId 어느 신고를 처리하다 열었는지. <b>필수다</b> — 이 값이 열람 자격이다
   */
  @Transactional
  public MessageSlice readMessages(MessageListQuery query, Long adminUserId, Long reportId) {
    requireReported(query.roomId(), reportId);

    List<AuthoredMessage> read = chatMessageRepository.findSlice(query);
    boolean hasNext = read.size() > query.size();
    List<AuthoredMessage> page = hasNext ? read.subList(0, query.size()) : read;

    auditLogRecorder.record(adminUserId, AuditKind.CHAT_READ, query.roomId(), detailOf(reportId));

    return new MessageSlice(views(page), nextCursor(page, hasNext), hasNext);
  }

  /**
   * 넘긴 신고가 이 방을 가리키는가.
   *
   * <p><b>없는 신고는 404 이고, 있는데 이 방을 안 가리키면 403 이다.</b> 존재를 숨기지 않는 이유는 이 경로가 관리자 전용이기 때문이다 — 관리자는
   * {@code GET /admin/reports} 로 신고 목록을 이미 본다. 숨겨서 얻는 것이 없고, 두 경우를 같은 403 으로 뭉치면 <b>관문의 403 과 구분되지
   * 않아</b> 권한 표 검사가 등급이 아니라 본문을 보게 된다.
   *
   * <p>메시지 신고면 그 메시지의 소속 방까지 따라간다 — 대화 한 줄을 신고했는데 앞뒤 맥락을 못 보면 판단할 수 없다.
   */
  private void requireReported(Long roomId, Long reportId) {
    Report report =
        reportRepository
            .findById(reportId)
            .orElseThrow(() -> new BusinessException(ReportErrorCode.REPORT_NOT_FOUND));

    if (!targets(report, roomId)) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_REPORTED);
    }
  }

  private boolean targets(Report report, Long roomId) {
    if (report.getTargetType() == ReportTargetType.ROOM) {
      return report.getTargetId().equals(roomId);
    }

    if (report.getTargetType() == ReportTargetType.MESSAGE) {
      return chatMessageRepository
          .findById(report.getTargetId())
          .map(message -> message.getRoomId().equals(roomId))
          .orElse(false);
    }

    return false;
  }

  private List<MessageView> views(List<AuthoredMessage> page) {
    return page.stream().map(this::view).toList();
  }

  /** 멤버 화면과 갈리는 한 줄 — 본문을 늘 싣는다. */
  private MessageView view(AuthoredMessage message) {
    return new MessageView(
        message.messageId(),
        message.senderId(),
        message.display(clock),
        message.content(),
        message.imageId(),
        message.status(),
        message.createdAt());
  }

  private static MessageCursor nextCursor(List<AuthoredMessage> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    return new MessageCursor(page.get(page.size() - 1).messageId());
  }

  private static String detailOf(Long reportId) {
    return "신고 %d 처리 중 채팅 대화 열람".formatted(reportId);
  }
}
