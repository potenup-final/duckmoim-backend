package com.duckmoim.chat.service;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.service.AuditLogRecorder;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 처리 결과로 메시지를 가린다 (AD-09).
 *
 * <p><b>{@code AdminCommentCommandService} 의 댓글 블라인드와 같은 구조다</b> (AD-07). 상태를 바꾸고 감사 로그를 남긴다.
 *
 * <p><b>신고 번호를 받지 않는다.</b> 대화 열람(AD-08)과 갈리는 지점이다 — 그쪽은 <b>남의 사적인 대화를 보는</b> 일이라 자격을 신고로 묶었지만, 가리는
 * 것은 보는 일이 아니다. 댓글 블라인드가 같은 이유로 {@code reportId} 를 안 받는다.
 *
 * <p><b>가리는 것이 신고 처리 결과와 자동으로 엮이지 않는다.</b> {@code result} 는 무엇으로 끝냈는지를 적는 칸이지 명령이 아니다 — {@code
 * COMMENT_BLINDED} 로 종결해도 댓글이 가려지지 않는 것과 같다 (API-설계.md 「2-7. 백오피스 (Admin)」).
 */
@Service
@RequiredArgsConstructor
public class AdminMessageBlindService {

  private final ChatMessageRepository chatMessageRepository;
  private final AuditLogRecorder auditLogRecorder;

  /**
   * 메시지 하나를 가린다.
   *
   * <p><b>{@code save} 를 부르지 않는다.</b> 이미 영속 상태라 트랜잭션이 끝날 때 더티 체킹이 반영한다.
   *
   * <p>전이 판정은 도메인이 쥔다 ({@code Message#blind}) — 이미 지워지거나 가려진 메시지는 409 다.
   */
  @Transactional
  public void blind(Long messageId, Long adminUserId) {
    Message message =
        chatMessageRepository
            .findById(messageId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND));

    message.blind();

    auditLogRecorder.record(adminUserId, AuditKind.MESSAGE_BLIND, messageId, "메시지 블라인드");
  }
}
