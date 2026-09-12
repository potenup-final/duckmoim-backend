package com.duckmoim.auth.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.safety.service.ActiveSanction;
import com.duckmoim.safety.service.SanctionQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 제재 중인 회원의 쓰기를 관문에서 막는다 (I-14). <b>비공개 경로에서는 읽기도 막는다</b> (STAR-84).
 *
 * <p><b>개별 엔드포인트에 적지 않는다.</b> API-설계.md 「1. 권한 등급」가 <i>"제재 중인 유저는 {@code SIGNUP} 등급 전체에서 차단된다
 * (I-14). 개별 엔드포인트에 적지 않고 인터셉터 한 곳에서 판정한다"</i> 고 정했다. 서비스마다 적으면 하나를 빠뜨렸을 때 아무도 모른다.
 *
 * <p><b>판정하지 않고 물어본다.</b> 여기서 {@code kind} 를 보지 않는다 — 무엇이 쓰기를 막는지는 {@code SanctionPolicy} 가 알고
 * (도메인-모델링.md 3.3), 이 클래스는 그 답을 HTTP 로 옮기기만 한다. 여기에 조건을 적으면 {@code WARNED} 가 쓰기 가능이라는 행이 두 곳에서
 * 관리된다.
 *
 * <p><b>컨텍스트 맵의 「Companion ──쓰기 가능 판정──▶ Safety」를 여기서 구현한다.</b> 화살표는 모집글·댓글 쓰기가 Safety 의 판정에 기대는
 * 관계를 뜻하고, 그 판정을 <b>어디서 부르는가</b>는 API 설계가 관문으로 정했다. Companion 의 서비스마다 부르면 그 화살표가 경로 수만큼 생긴다.
 *
 * <p><b>등록은 {@code SanctionGateConfig} 가 한다.</b> 어느 경로에 거는지가 이 클래스가 아니라 그쪽에 있다 — {@code
 * SecurityConfig} 의 {@code SIGNUP} 쓰기 목록과 나란히 두어야 둘이 어긋난 것이 보인다.
 *
 * <p><b>그래서 「비공개 경로인가」도 여기 적지 않고 생성자로 받는다.</b> {@code /chat-rooms} 를 이 클래스에 적으면 경로 지식이 두 곳에 생기고,
 * 다음에 비공개 경로가 늘 때 한쪽만 고치게 된다.
 *
 * <p><b>빈이 아니다.</b> {@code @Component} 를 붙이면 {@code @WebMvcTest} 가 {@code HandlerInterceptor} 를
 * 슬라이스에 집어 가는데, 그러면 컨트롤러 슬라이스 테스트가 전부 Safety 빈을 요구하게 된다. 설정이 직접 만든다.
 */
@RequiredArgsConstructor
public class SanctionGateInterceptor implements HandlerInterceptor {

  private final SanctionQueryService sanctionQueryService;

  /**
   * 이 등록이 <b>읽기까지</b> 보는가.
   *
   * <p>참이면 비공개 경로다 — {@code BANNED} 의 읽기가 막힌다 (도메인-모델링.md 「6. 라이프사이클」의 제재 축 「비공개 읽기」 열). 거짓이면 공개
   * 읽기가 섞인 경로라 쓰기만 본다.
   */
  private final boolean guardsReading;

  /**
   * 제재 중이면 {@code USER_SANCTIONED} 403 이고, <b>{@code message} 에 사유가 실린다</b> (AD-04 · AU-12).
   *
   * <p><b>공개 읽기가 섞인 경로에서는 읽기를 막지 않는다.</b> {@code /api/v1/posts/**} · {@code /api/v1/comments/**} 에는
   * 목록·상세 조회가 함께 걸리는데, 거기서 메서드를 가리지 않으면 <b>경고받은 사람이 남의 모집글도 못 보게 된다.</b> 그 경로들은 {@code
   * guardsReading} 이 거짓으로 등록된다.
   *
   * <p><b>비공개 경로에서는 읽기도 본다</b> (STAR-84). 지금 그런 등록은 채팅방 하나이고, 막히는 것은 {@code BANNED} 뿐이다.
   *
   * <p>인증이 없으면 그냥 보낸다. 이 인터셉터가 걸리는 경로의 쓰기는 이미 {@code SIGNUP} 등급이라 관문이 401·403 으로 끝냈지만, 같은 경로의 읽기는
   * 비회원에게 열려 있어 (API-설계.md 「2-4. 모집글」·「2-5. 댓글」) 인증 없는 요청이 실제로 여기까지 온다.
   */
  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {

    boolean reading = isReading(request);
    if (reading && !guardsReading) {
      return true;
    }

    Long userId = currentUserId();
    if (userId == null) {
      return true;
    }

    if (!allows(userId, reading)) {
      throw new BusinessException(UserErrorCode.USER_SANCTIONED, reasonOf(userId));
    }

    return true;
  }

  /**
   * 판정을 고르기만 한다.
   *
   * <p>여기서 {@code kind} 를 보지 않는 것이 이 클래스의 규칙이다 — 무엇이 무엇을 막는지는 {@code SanctionPolicy} 가 안다.
   */
  private boolean allows(Long userId, boolean reading) {
    return reading
        ? sanctionQueryService.canReadPrivate(userId)
        : sanctionQueryService.canWrite(userId);
  }

  /**
   * 읽는 요청인가.
   *
   * <p>{@code SecurityConfig} 가 이 경로들의 쓰기를 {@code POST} · {@code PATCH} · {@code DELETE} 로 정했다. 그
   * 셋만 골라 막지 않고 「읽기가 아니면」으로 뒤집어 적는 이유는, 쓰기 메서드가 하나 늘었을 때 <b>조용히 안 막히는 쪽</b>이 아니라 막히는 쪽으로 기울기 때문이다.
   */
  private static boolean isReading(HttpServletRequest request) {
    String method = request.getMethod();

    return HttpMethod.GET.matches(method)
        || HttpMethod.HEAD.matches(method)
        || HttpMethod.OPTIONS.matches(method);
  }

  /**
   * 응답에 실을 문장.
   *
   * <p>사유는 본인에게 보여주는 정보라 노출해도 된다 (AD-04 · AU-12). 막힌 직후라 제재가 반드시 있지만, 그 사이 풀렸다면 코드의 고정 문구로 답한다.
   */
  private String reasonOf(Long userId) {
    return sanctionQueryService
        .findActive(userId)
        .map(ActiveSanction::reason)
        .orElse(UserErrorCode.USER_SANCTIONED.getMessage());
  }

  private static Long currentUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    return Optional.ofNullable(authentication)
        .map(Authentication::getPrincipal)
        .filter(AuthUser.class::isInstance)
        .map(AuthUser.class::cast)
        .map(AuthUser::userId)
        .orElse(null);
  }
}
