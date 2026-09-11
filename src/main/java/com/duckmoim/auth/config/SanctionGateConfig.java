package com.duckmoim.auth.config;

import com.duckmoim.auth.presentation.SanctionGateInterceptor;
import com.duckmoim.safety.service.SanctionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * I-14 를 어느 경로에 거는지 (제재 중 쓰기 차단).
 *
 * <p><b>{@code SecurityConfig} 의 {@code SIGNUP_WRITE} 와 같은 목록이어야 한다.</b> API-설계.md 가 <i>"제재 중인 유저는
 * {@code SIGNUP} 등급 전체에서 차단된다"</i> 고 정했다. 둘이 어긋나면 등급은 있는데 제재는 안 막는 경로가 생기므로, 목록을 나란히 두어 눈으로 대조할 수
 * 있게 한다.
 *
 * <p><b>{@code /users/me} 는 걸지 않는다.</b> 그쪽은 {@code SIGNUP} 등급이지만 제재 안내를 <b>보여주는</b> 경로다 (AU-12) —
 * 막으면 정지당한 사람이 자기가 왜 정지됐는지 볼 수 없다. 프로필 수정도 같은 경로에 있어 함께 열리는데, 도메인 6장 제재 축 표의 「쓰기」는 모집글·댓글을 뜻한다
 * (I-14 의 문장이 <i>"신규 모집글·댓글을 작성할 수 없다"</i> 다).
 *
 * <p>읽기는 어느 등급에서도 막지 않는다. {@code BANNED} 가 읽기까지 막는 것은 로그인 자체를 막는 일이라 AU 쪽 소관이다.
 *
 * <p><b>인터셉터를 {@code ObjectProvider} 로 받는다.</b> {@code @WebMvcTest} 슬라이스가 {@code WebMvcConfigurer}
 * 는 집어 가면서 {@code @Component} 인 인터셉터는 안 가져와, 그대로 두면 컨트롤러 슬라이스 테스트가 전부 Safety 빈을 요구하게 된다. 없으면 등록을
 * 건너뛴다.
 *
 * <p><b>그래서 「조용히 안 걸린 상태」가 가능해진다.</b> 그것을 {@code SanctionGateTest} 가 막는다 — 전체 컨텍스트로 진짜 요청을 쏘므로, 등록이
 * 빠지면 그 검사가 빨간불이다. 실제로 경로를 비워 다섯 케이스가 뒤집히는 것을 확인했다.
 */
@Configuration
@RequiredArgsConstructor
public class SanctionGateConfig implements WebMvcConfigurer {

  /**
   * I-14 가 막는 것은 <b>신규 모집글과 댓글</b>이고, 2차부터 <b>채팅 쓰기</b>가 는다 (도메인-모델링.md 「5. 불변식」 · 「3. 애그리게이트 경계」).
   *
   * <p>신고({@code /api/v1/reports})는 {@code SecurityConfig} 의 쓰기 목록에 있지만 여기 없다. 제재당한 사람이 남을 신고하는 길까지
   * 막으면 1차 안전장치가 신고뿐인데 그 창구가 좁아진다 — 불변식의 문장도 모집글·댓글 둘로 한정돼 있다.
   *
   * <p><b>채팅은 방 아래 전체를 건다</b> (CH-20). 인터셉터가 「읽기가 아니면 막는다」로 뒤집혀 있어, 쓰기 메서드가 하나 늘었을 때 조용히 열리는 쪽이 아니라
   * 막히는 쪽으로 기운다. 지금 이 접두어 아래 쓰기는 메시지 전송 하나다 (CH-07).
   *
   * <p><b>전송 service 에는 제재 판정이 없다.</b> 있어야 하는 것이 아니라 없는 것이 맞다 — 판정 자리를 관문 하나로 모으는 것이 I-14 의 설계이고,
   * 서비스마다 적으면 하나를 빠뜨렸을 때 아무도 모른다.
   *
   * <p><b>그래서 퇴장(CH-04)을 아래에서 도로 뺀다.</b> 퇴장은 {@code DELETE} 라 이 목록에 걸리는데, 정지당한 사람이 방을 나가지 못하는 것은
   * 신고와 탈퇴를 일부러 뺀 판단과 같은 줄에 있다 — 제재가 막는 것은 <b>새로 쓰는 일</b>이지 관계를 끊는 일이 아니다.
   */
  private static final String[] SANCTIONED_WRITE = {
    "/api/v1/posts/**", "/api/v1/comments/**", "/api/v1/chat-rooms/**"
  };

  /**
   * 위 접두어 아래이면서 막지 않는 경로 (CH-04).
   *
   * <p><b>제재가 막는 것은 새로 쓰는 일이지 관계를 끊는 일이 아니다</b> (도메인-모델링.md 「3.3 경계를 넘는 불변식」). 신고({@code
   * /api/v1/reports})와 탈퇴를 목록에서 뺀 것과 같은 판단이고, 방을 나가는 것은 그 둘에 가깝다 — 정지당한 사람을 대화방에 가둬 두는 것이 제재의 목적일 수
   * 없다.
   *
   * <p><b>경로로 빼고 메서드로 빼지 않는다.</b> 인터셉터의 제외 목록이 경로 단위라 그렇기도 하지만, 그 편이 안전한 방향이기도 하다 — 이 경로에 사는 것은 퇴장
   * {@code DELETE} 하나이고, 여기에 쓰기를 새로 얹는 일은 없다. 반대로 메서드로 빼면 앞으로 생기는 모든 {@code DELETE} 가 함께 열린다.
   *
   * <p><b>{@code SecurityConfig} 에는 같은 예외가 없다.</b> 그쪽은 등급이라 나가기도 {@code SIGNUP} 이 맞다 — 가입을 마치지 않은
   * 계정은 애초에 방 멤버가 아니라 나갈 방도 없다. 두 목록이 여기서만 갈리는 이유가 그것이다.
   */
  private static final String[] SANCTIONED_EXCEPT = {"/api/v1/chat-rooms/*/members/me"};

  private final ObjectProvider<SanctionQueryService> sanctionQueryService;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    sanctionQueryService.ifAvailable(
        service ->
            registry
                .addInterceptor(new SanctionGateInterceptor(service))
                .addPathPatterns(SANCTIONED_WRITE)
                .excludePathPatterns(SANCTIONED_EXCEPT));
  }
}
