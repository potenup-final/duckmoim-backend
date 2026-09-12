package com.duckmoim.chat.domain;

/**
 * 저장소에 올라온 객체의 메타데이터 (CH-14).
 *
 * <p><b>서버가 파일을 못 보기 때문에 존재하는 타입이다.</b> 바이트는 브라우저와 S3 사이에서만 오가고, 확정 단계가 판정할 재료는 저장소에 물어본 이 둘뿐이다.
 *
 * <p>{@code UploadedImage}(AU-08)와 모양이 같은데 돌려 쓰지 않는다 — 그쪽은 {@code identity.domain} 이고, 저장소 포트를 합치지
 * 않기로 한 것과 같은 근거다 (계획서 8.1).
 */
public record UploadedChatImage(String contentType, long contentLength) {}
