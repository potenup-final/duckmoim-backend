package com.duckmoim.auth.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 카카오를 부르는 {@link RestClient} 하나 (AU-01).
 *
 * <p><b>왜 빈으로 꺼냈는가</b> — 타임아웃을 클라이언트 생성자 안에서 걸면 테스트가 그 자리를 갈아끼울 수 없다. {@code MockRestServiceServer}
 * 는 요청 팩터리를 자기 것으로 바꿔 끼워 동작하는데, 생성자가 팩터리를 다시 덮으면 대역이 무력해진다. 그래서 <b>팩터리 설정은 여기, 요청 조립은 클라이언트</b>로
 * 갈랐다.
 *
 * <p><b>타임아웃이 없으면 스레드가 무한정 매달린다.</b> 카카오 호출은 트랜잭션 밖에 있어 DB 커넥션은 쥐지 않지만 톰캣 스레드는 그대로 잡는다. 카카오가 느려지는
 * 순간 로그인 요청이 스레드 풀을 채우고, 그러면 <b>로그인과 무관한 요청까지</b> 대기한다.
 */
@Configuration
public class KakaoClientConfig {

  @Bean
  public RestClient kakaoRestClient(
      RestClient.Builder builder,
      @Value("${duckmoim.kakao.connect-timeout}") Duration connectTimeout,
      @Value("${duckmoim.kakao.read-timeout}") Duration readTimeout) {

    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);

    return builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();
  }
}
