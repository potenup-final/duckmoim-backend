package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.SignedChatImageUrl;

/**
 * 사진 한 장의 발급 결과 (CH-15).
 *
 * <p><b>메시지 번호를 함께 준다.</b> 일괄 발급이라 응답 한 건이 어느 말풍선의 사진인지 말해 주지 않으면 화면이 붙일 자리를 모른다 — 볼 수 없는 것은 응답에서
 * 빠지므로 <b>순서로 맞출 수 없다.</b>
 *
 * <p>엔티티를 service 밖으로 내보내지 않기 위한 결과 객체다 ({@code ChatImageUpload} 와 같은 배치).
 */
public record ChatImageView(Long messageId, SignedChatImageUrl signed) {}
