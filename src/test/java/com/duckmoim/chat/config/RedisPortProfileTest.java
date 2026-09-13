package com.duckmoim.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * 프로필마다 Redis 가 어느 포트를 가리키는가 (STAR-139).
 *
 * <p><b>운영 기본값이 로컬 값을 따라가 채팅 스트림이 운영에서 열리지 않았다.</b> 앱이 {@code 6380} 으로 붙으려 했는데 운영 Redis 는 {@code
 * 6379} 에 떠 있었다. 다른 테스트가 이것을 못 잡은 이유는 Testcontainers 가 포트를 직접 넣어 <b>기본값을 한 번도 타지 않기</b> 때문이다. 그래서
 * 여기는 설정 파일 자체를 읽는다.
 *
 * <p><b>컨텍스트를 띄우지 않는다.</b> 보려는 것은 두 YAML 이 겹쳤을 때 풀리는 값 하나이고, {@code @SpringBootTest} 를 더하면 캐시에 남는
 * 컨텍스트가 하나 늘어난다.
 *
 * <p><b>시스템 환경변수를 섞지 않는다.</b> 개발자 PC 에 {@code REDIS_PORT} 가 걸려 있으면 그 값이 이겨 테스트가 PC 에 따라 갈린다. 운영에서 그
 * 변수가 <b>넘어오지 않는다</b>는 것이 이 버그의 조건이라, 없는 상태를 재현한다.
 */
@DisplayName("Redis 포트 기본값")
class RedisPortProfileTest {

  private static final String PORT = "spring.data.redis.port";

  @DisplayName("운영 프로필은 운영 Redis 의 포트 6379 로 붙는다.")
  @Test
  void prodProfile() throws IOException {
    // given — 프로필 파일이 기본 파일보다 앞에 온다. 스프링이 겹칠 때 쓰는 우선순위와 같다
    MutablePropertySources sources = new MutablePropertySources();
    load(sources, "application-prod.yml");
    load(sources, "application.yml");

    // when
    String port = new PropertySourcesPropertyResolver(sources).getProperty(PORT);

    // then
    assertThat(port).isEqualTo("6379");
  }

  @DisplayName("로컬은 compose 가 연 포트 6380 으로 붙는다.")
  @Test
  void defaultProfile() throws IOException {
    // given — 운영을 고치려고 기본 파일을 바꾸면 로컬 bootRun 이 반대로 깨진다. 그쪽을 막는다
    MutablePropertySources sources = new MutablePropertySources();
    load(sources, "application.yml");

    // when
    String port = new PropertySourcesPropertyResolver(sources).getProperty(PORT);

    // then
    assertThat(port).isEqualTo("6380");
  }

  private static void load(MutablePropertySources sources, String name) throws IOException {
    new YamlPropertySourceLoader()
        .load(name, new ClassPathResource(name))
        .forEach(sources::addLast);
  }
}
