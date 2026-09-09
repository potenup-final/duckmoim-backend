package com.duckmoim.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각을 주입 가능한 값으로 만든다.
 *
 * <p>{@code LocalDate.now()} 를 코드 안에서 직접 부르면 그 로직을 테스트가 실행 날짜에 맡기게 된다. 행사 목록은 "오늘 끝난 행사인가" 로 결과가
 * 갈려서, 고정할 수 없으면 경계 검증이 어제와 오늘 다르게 돈다.
 *
 * <p>KST 인 것은 도메인 4장이 정했다 — 저장은 UTC 지만 <b>판정 기준은 KST</b> 다. 행사 종료일은 날짜 컬럼이라 어느 시간대의 "오늘" 인지가 결과를
 * 바꾼다.
 */
@Configuration
public class ClockConfig {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  @Bean
  public Clock clock() {
    return Clock.system(KST);
  }
}
