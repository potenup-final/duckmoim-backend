package com.duckmoim.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업을 켠다.
 *
 * <p>애플리케이션 클래스에 {@code @EnableScheduling} 을 붙이지 않은 이유 — <b>스케줄링이 켜졌다는 사실이 config 에 보여야 다음 배치가 찾을
 * 자리가 생긴다.</b> {@code ClockConfig} · {@code CorsConfig} 와 같은 판단이다.
 *
 * <p>지금 이것으로 도는 것은 만남시각 경과 마감(PO-14) 하나다.
 *
 * <p><b>스레드 풀을 따로 두지 않는다.</b> 기본 스케줄러는 스레드 하나라 작업이 둘이 되면 서로 밀리는데, 지금은 하나이고 5분 주기에 수 초짜리 작업이라 겹칠 일이
 * 없다. 둘째 배치가 들어오는 티켓에서 정한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
