package com.duckmoim.chat.service;

/**
 * 발급 결과 (CH-14).
 *
 * <p>엔티티를 service 밖으로 내보내지 않기 위한 결과 객체다 ({@code SentMessage} 와 같은 배치).
 *
 * <p><b>객체 키를 담지 않는다.</b> {@code ProfileImageUpload}(AU-08)는 키를 내려주고 확정 요청이 그것을 되돌려주는데, 채팅은 <b>행
 * 번호</b>를 손잡이로 쓴다 — 키가 클라이언트를 지나지 않으면 위조할 값이 없고, 확정 때 「남의 키인가」를 접두어로 되짚을 필요도 없다. 그 판정은 행이 들고 있다.
 *
 * @param imageId 확정과 전송에 쓰는 손잡이
 * @param uploadUrl 브라우저가 {@code PUT} 할 서명된 주소. 이 주소로 바이트가 저장소에 직접 간다
 * @param expiresInSeconds 서명의 남은 수명. 클라이언트가 재발급 시점을 판단한다
 */
public record ChatImageUpload(Long imageId, String uploadUrl, long expiresInSeconds) {}
