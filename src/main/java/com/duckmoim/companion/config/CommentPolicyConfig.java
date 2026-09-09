package com.duckmoim.companion.config;

import com.duckmoim.companion.domain.CommentActionPolicy;
import com.duckmoim.companion.domain.CommentVisibilityPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 댓글 판정기 둘을 빈으로 올린다.
 *
 * <p>{@code @Component} 를 도메인 서비스에 붙이지 않는 이유 — 게이트가 <b>domain 은 Spring 에 의존하지 않는다</b> 로 막는다 ({@code
 * DOMAIN_IS_FRAMEWORK_FREE}). 컨텍스트 없이 단위 테스트할 수 있어야 한다는 규칙이고, 판정기 전 조합 테스트가 그 덕에 밀리초 단위로 돈다.
 *
 * <p>둘 다 상태가 없어 한 번 만들어 공유해도 된다.
 */
@Configuration
public class CommentPolicyConfig {

  @Bean
  public CommentVisibilityPolicy commentVisibilityPolicy() {
    return new CommentVisibilityPolicy();
  }

  @Bean
  public CommentActionPolicy commentActionPolicy() {
    return new CommentActionPolicy();
  }
}
