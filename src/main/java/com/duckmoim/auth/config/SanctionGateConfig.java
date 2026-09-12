package com.duckmoim.auth.config;

import com.duckmoim.auth.presentation.SanctionGateInterceptor;
import com.duckmoim.safety.service.SanctionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 제재 관문을 어느 경로에 거는지 — 쓰기 차단(I-14)과 <b>비공개 읽기 차단</b>(STAR-84).
 *
 * <p><b>{@code SecurityConfig} 의 {@code SIGNUP_WRITE} 와 같은 목록이어야 한다.</b> API-설계.md 가 <i>"제재 중인 유저는
 * {@code SIGNUP} 등급 전체에서 차단된다"</i> 고 정했다. 둘이 어긋나면 등급은 있는데 제재는 안 막는 경로가 생기므로, 목록을 나란히 두어 눈으로 대조할 수
 * 있게 한다.
 *
 * <p><b>{@code /users/me} 는 걸지 않는다.</b> 그쪽은 {@code SIGNUP} 등급이지만 제재 안내를 <b>보여주는</b> 경로다 (AU-12) —
 * 막으면 정지당한 사람이 자기가 왜 정지됐는지 볼 수 없다. 프로필 수정도 같은 경로에 있어 함께 열리는데, 도메인 6장 제재 축 표의 「쓰기」는 모집글·댓글을 뜻한다
 * (I-14 의 문장이 <i>"신규 모집글·댓글을 작성할 수 없다"</i> 다).
 *
 * <p><b>등록이 둘인 이유는 읽기다.</b> 모집글·댓글 경로에는 공개 읽기가 섞여 있어 메서드를 가리지 않으면 경고받은 사람이 남의 글도 못 보게 된다. 채팅방은 전부
 * 비공개라 {@code BANNED} 의 읽기를 막을 수 있고, 막을 실익도 거기만 있다 — 공개 글은 비회원에게 열려 있어 로그아웃하면 그대로 보인다 (도메인-모델링.md
 * 「6. 라이프사이클」의 제재 축).
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
   * <p><b>채팅은 여기 없다</b> (2026-09-11 · STAR-84). {@link #SANCTIONED_PRIVATE} 로 옮겼다 — 그 경로는 읽기까지 막아야
   * 해서 등록이 갈린다.
   *
   * <p><b>전송 service 에는 제재 판정이 없다.</b> 있어야 하는 것이 아니라 없는 것이 맞다 — 판정 자리를 관문 하나로 모으는 것이 I-14 의 설계이고,
   * 서비스마다 적으면 하나를 빠뜨렸을 때 아무도 모른다.
   */
  private static final String[] SANCTIONED_WRITE = {"/api/v1/posts/**", "/api/v1/comments/**"};

  /**
   * 비공개 경로 — 쓰기와 <b>{@code BANNED} 의 읽기</b>를 함께 막는다 (STAR-84 · CH-20).
   *
   * <p><b>위 목록에 함께 넣지 않는다.</b> 이 등록이 쓰기도 보므로 두 곳에 넣으면 중복이고, 제재 조회가 요청마다 두 번 난다.
   *
   * <p>채팅만 있는 이유는 지금 비공개 읽기가 거기뿐이기 때문이다. 알림과 내 활동 내역도 {@code SIGNUP} 이지만 <b>본인 데이터라 막을 실익이 없다</b> —
   * 제재당한 사람이 자기 알림을 못 보게 해서 얻는 것이 없다.
   *
   * <p><b>아래 각주는 STAR-112 가 쓰기 목록에 적어 둔 것이다.</b> 채팅이 이 상수로 옮겨 오면서 함께 왔다 — 가리키는 경로가 여기라 그대로 둘 수 없다.
   *
   * <p><b>채팅은 방 아래 전체를 건다</b> (CH-20). 인터셉터가 「읽기가 아니면 막는다」로 뒤집혀 있어, 쓰기 메서드가 하나 늘었을 때 조용히 열리는 쪽이 아니라
   * 막히는 쪽으로 기운다.
   *
   * <p><b>그 「하나 늘었을 때」가 곧바로 왔다</b> — 메시지 삭제({@code DELETE .../messages/{id}}, CH-12 · STAR-112)가 이
   * 접두어 아래 두 번째 쓰기다. 그래서 지금은 <b>제재 중인 사람이 자기 메시지를 지울 수 없다.</b>
   *
   * <p><b>그대로 둔다. 다만 옳은지는 열려 있다.</b> 아래 {@code SANCTIONED_EXCEPT} 가 적은 기준(<i>"제재가 막는 것은 새로 쓰는 일이지
   * 관계를 끊는 일이 아니다"</i>)으로 재면 삭제도 막을 것이 아니다 — I-14 의 문장도 CH-20 의 문장도 「작성」·「쓸 수」이고, 소프트 삭제라 <b>열어도
   * 증거가 사라지지 않는다</b> (본문이 표에 남고 AD-08 이 그대로 읽는다).
   *
   * <p><b>그럼에도 이 티켓에서 예외 목록을 넓히지 않는다.</b> 제재가 무엇을 막는지를 정하는 것은 CH-20 의 몫이고, <b>제재 면제를 늘리는 일은 조용히
   * 곁다리로 할 것이 아니다</b> — 넓히는 방향의 변경이라 한 줄이라도 그 자체로 판단이 필요하다. 아래 CH-04 예외가 STAR-102 에서 <b>자기 티켓의
   * 결정으로</b> 들어온 것과 같은 모양이어야 한다.
   *
   * <p><b>넣는다면 {@code chat-rooms/&#42;/messages/&#42;} 한 줄이다.</b> 아래 「경로로 빼고 메서드로 빼지 않는다」를 지키는
   * 형태이고, 그 깊이에 사는 것이 삭제 하나다 — CH-15 의 이미지 서명은 한 칸 더 깊어 걸리지 않는다.
   *
   * <p>(별표를 {@code &#42;} 로 적은 것은 자바독 안에서 별표와 빗금이 붙으면 주석이 거기서 닫히기 때문이다.)
   */
  private static final String[] SANCTIONED_PRIVATE = {"/api/v1/chat-rooms/**"};

  /**
   * {@link #SANCTIONED_PRIVATE} 아래이면서 막지 않는 경로 (CH-04).
   *
   * <p><b>이 제외는 비공개 등록에 붙는다.</b> 한때 쓰기 목록에 채팅이 함께 있어 그쪽에 붙어 있었는데, STAR-84 가 채팅을 옮기면서 같이 옮겼다 — 안
   * 옮겼으면 {@code /posts} · {@code /comments} 에 채팅 경로를 제외하는 꼴이 되어 <b>에러도 경고도 없이 아무것도 안 한다.</b>
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

  /**
   * 방 목록 — 비공개 등록에서 빼고 <b>쓰기 등록으로 옮긴다</b> (CH-04 · STAR-84).
   *
   * <p><b>위 퇴장 예외가 닿을 수 있는 문이 되게 하는 것이 이 상수다.</b> 목록까지 막으면 {@code BANNED} 은 <b>나갈 방의 번호를 얻을 길이
   * 없다</b> — {@code roomId} 를 담는 응답이 전부 이 접두어 아래이고, 알림({@code postId} · {@code commentId})에도 모집글
   * 번호로 방을 찾는 경로에도 없다. 관문은 열려 있는데 아무도 그 문에 닿지 못하는 상태였고, <b>없는 번호를 쏘는 검사는 그것을 잡지 못했다.</b>
   *
   * <p><b>열어도 되는 이유는 목록이 담는 것이 좁기 때문이다.</b> 방 번호 · 모집글 번호 · 모집글 제목 · 만남시각 · 멤버 <b>수</b> 다섯이고, 대화도
   * 멤버 신원도 없다. 이 티켓이 막으려던 것은 <i>"제재당한 사람이 피해자와 같은 방을 계속 읽는 자리"</i> 이고 그것은 상세와 메시지다.
   *
   * <p><b>제외가 아니라 이동이다.</b> 목록 경로를 그냥 빼면 쓰기도 함께 열린다 — 지금 이 경로에 쓰기가 없어도 fail-open 모양을 남기지 않는다.
   *
   * <p>정확 경로라 {@code /chat-rooms/&#123;id&#125;} 이하는 그대로 막힌다.
   */
  private static final String[] SANCTIONED_ROOM_LIST = {"/api/v1/chat-rooms"};

  private final ObjectProvider<SanctionQueryService> sanctionQueryService;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    sanctionQueryService.ifAvailable(
        service -> {
          registry
              .addInterceptor(new SanctionGateInterceptor(service, false))
              .addPathPatterns(SANCTIONED_WRITE)
              .addPathPatterns(SANCTIONED_ROOM_LIST);

          registry
              .addInterceptor(new SanctionGateInterceptor(service, true))
              .addPathPatterns(SANCTIONED_PRIVATE)
              .excludePathPatterns(SANCTIONED_EXCEPT)
              .excludePathPatterns(SANCTIONED_ROOM_LIST);
        });
  }
}
