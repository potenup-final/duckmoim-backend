package com.duckmoim.chat.service;

/**
 * EXIF 를 벗길 사진 한 장 (CH-16).
 *
 * <p>엔티티를 service 밖으로 내보내지 않기 위한 값이다. 워커는 트랜잭션 밖에서 저장소를 부르므로 영속성 컨텍스트가 없고, 필요한 것도 이 둘뿐이다.
 */
public record ExifTarget(Long imageId, String objectKey) {}
