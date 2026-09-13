package com.duckmoim.chat.domain;

/**
 * 저장소에서 내려받은 사진 한 장 (CH-16).
 *
 * <p><b>{@code etag} 를 함께 든다.</b> 덮어쓸 때 「내려받은 그대로인가」를 저장소에 묻는 값이다 ({@link
 * ChatImageStorage#overwriteIfUnchanged}) — 내려받고 벗기는 사이에 고아 정리가 객체를 지웠으면 그 조건이 거짓이 되어 <b>행 없는 객체를
 * 되살리지 않는다.</b>
 */
public record StoredChatImage(byte[] bytes, String contentType, String etag) {}
