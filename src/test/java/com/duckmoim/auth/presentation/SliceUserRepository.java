package com.duckmoim.auth.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.infra.UserRepository;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 컨트롤러 슬라이스에서 회원 조회를 대신한다.
 *
 * <p>{@code AuthenticationService} 가 토큰마다 회원을 읽어 무효화 시각을 보는데(AU-04), {@code @WebMvcTest} 에는 JPA 도
 * DB 도 없다. 이것이 없으면 <b>슬라이스 다섯 개가 컨텍스트 생성부터 실패한다</b> — 그중 셋은 다른 담당의 파일이다.
 *
 * <p>「토큰이 무효화되지 않은 회원」 하나만 돌려준다. 무효화 판정 자체는 {@code UserTest} 가 단위로, 로그아웃 뒤 401 은 {@code
 * AuthGatewayTest} 가 진짜 DB 로 본다. 여기서 볼 일이 아니다.
 */
@TestConfiguration
public class SliceUserRepository {

  @Bean
  public UserRepository userRepository() {
    User user = mock(User.class);
    given(user.isTokenInvalidated(any())).willReturn(false);

    UserRepository userRepository = mock(UserRepository.class);
    given(userRepository.findById(any())).willReturn(Optional.of(user));

    return userRepository;
  }
}
