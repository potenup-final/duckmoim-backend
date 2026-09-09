package com.duckmoim.identity.domain;

/**
 * 저장소에 실제로 올라간 객체의 메타데이터 (AU-08).
 *
 * <p>확정 단계가 이것을 읽어 <b>클라이언트가 발급 때 선언한 값과 실제가 같은지</b> 본다. 선언만 검사하면 5MB 라 하고 50MB 를 올려도 통과한다.
 */
public record UploadedImage(String contentType, long contentLength) {}
