package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.identity.domain.AuthorDisplay;
import java.time.LocalDateTime;

/**
 * 목록에 실릴 메시지 한 건 (CH-09).
 *
 * <p>엔티티와 저장소 프로젝션을 service 밖으로 내보내지 않기 위한 결과 객체다 (아키텍처 컨벤션 「service · 금지」).
 *
 * <p><b>{@code content} 가 {@code null} 인 것이 곧 「지운 메시지」다</b> (CH-12). 응답 조립이 이 값을 보고 <b>키 자체를 뺀다</b>
 * — API-설계.md 가 <i>"권한이 없으면 null 이 아니라 키 자체가 빠진다"</i> 로 정했고 자리표시자도 같은 형태다 (CM-08).
 *
 * <p><b>{@code status} 를 함께 싣는 이유</b> — {@code content} 가 없다는 것만으로는 화면이 「지워졌습니다」를 그릴 근거가 없다. 댓글 응답도
 * 같은 이유로 {@code status} 를 내린다.
 *
 * @param content 지운 메시지면 null 이다
 */
public record MessageView(
    Long messageId,
    Long senderId,
    AuthorDisplay sender,
    String content,
    MessageStatus status,
    LocalDateTime createdAt) {}
