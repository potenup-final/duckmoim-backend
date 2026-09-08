package com.duckmoim.catalog.service;

import com.duckmoim.catalog.domain.EventCrawl;
import java.util.List;

/**
 * 크롤러가 한 번에 보낸 행사들 (EV-03).
 *
 * <p>요청 DTO 를 service 로 그대로 넘기지 않기 위한 커맨드다 (service 규칙).
 *
 * <p><b>{@code districtCode} 가 {@link EventCrawl} 밖에 있다.</b> 크롤러는 지역 코드 문자열을 보내는데 {@code Event} 는
 * {@code regionId} 를 든다. 그 변환은 {@code Region} 조회라 애그리게이트 밖이고(도메인 3.2), 그래서 도메인 값 객체에 넣지 않고 여기서 짝으로
 * 들고 있다.
 */
public record EventIngestCommand(List<Item> items) {

  public record Item(EventCrawl crawl, String districtCode) {}
}
