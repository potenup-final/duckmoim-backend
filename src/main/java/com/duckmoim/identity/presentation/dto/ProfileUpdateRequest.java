package com.duckmoim.identity.presentation.dto;

import com.duckmoim.identity.service.ProfileUpdateCommand;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 프로필 수정 요청 (AU-08).
 *
 * <p><b>{@code PATCH} 라 안 보낸 필드는 안 바뀐다.</b> 둘 다 {@code null} 을 허용하고, 그 뜻은 {@link
 * com.duckmoim.identity.domain.Profile} 에 적어 두었다.
 *
 * <p><b>닉네임은 {@code @NotBlank} 를 쓸 수 없고 {@code @Size(min = 1)} 로도 부족하다.</b> 넷의 차이가 이 계약의 핵심이다.
 *
 * <table border="1">
 *   <caption>제약 선택</caption>
 *   <tr><th>제약</th><th>{@code null}</th><th>{@code ""}</th><th>{@code "   "}</th></tr>
 *   <tr><td>{@code @NotBlank}</td><td>거부</td><td>거부</td><td>거부</td></tr>
 *   <tr><td>{@code @Size(min = 1)}</td><td><b>허용</b></td><td>거부</td><td><b>통과</b> ← 결함</td></tr>
 *   <tr><td>{@code @Size(max = ...)}</td><td>허용</td><td>허용</td><td>통과</td></tr>
 *   <tr><td><b>{@code @Size(max)} + {@code @Pattern}</b></td><td><b>허용</b></td><td>거부</td><td>거부</td></tr>
 * </table>
 *
 * <p>닉네임은 <b>안 보낼 수는 있어도 비울 수는 없다</b>. 한줄소개는 <b>비울 수 있다</b>. Bean Validation 이 {@code null} 을
 * {@code @Size} · {@code @Pattern} 검사에서 모두 건너뛰는 성질을 그대로 쓴다.
 *
 * <p><b>{@code @Size(min = 1)} 만으로는 공백이 통과한다.</b> 길이 검사라 {@code " "} 를 3자로 세고, 그 값이 그대로 저장되면 화면에
 * 이름이 없는 회원이 생긴다 — PR #57 리뷰에서 지적받아 <b>실측하고(200 이 나왔다) 고쳤다.</b>
 *
 * <p>정규식이 {@code (?s).*\S.*} 인 것은 <b>{@code String.isBlank()} 와 정확히 같은 판정</b>을 만들기 위함이다 — 공백이 아닌
 * 글자가 하나라도 있으면 통과한다. {@code (?s)} 를 붙이지 않으면 {@code .} 가 줄바꿈을 넘지 못해 {@code "a\nb"} 까지 거절하게 되고, 그러면
 * 가입(AU-05)의 {@code @NotBlank} 와 판정이 갈린다.
 *
 * <p><b>출생연도 필드를 두지 않는다.</b> API 설계 2-2 가 <i>"출생연도는 받지 않는다"</i> 로 정했다. 필드를 두고 400 을 내면 그 필드가 언젠가 열릴
 * 것처럼 보인다 — 두지 않으면 계약에 없다.
 *
 * <p><b>{@code profileImageUrl} 도 두지 않는다.</b> 열면 클라이언트가 임의 URL 을 박을 수 있고, 그 순간 결정 D-2(서버가 기본 이미지
 * URL 을 만들지 않는다)가 의미를 잃는다. 채우는 경로는 업로드(I 티켓)다.
 */
public record ProfileUpdateRequest(
    @Size(max = 20, message = "닉네임은 20자를 넘을 수 없습니다.")
        @Pattern(regexp = "(?s).*\\S.*", message = "닉네임은 공백만으로 둘 수 없습니다.")
        String nickname,
    @Size(max = 100, message = "한줄소개는 100자를 넘을 수 없습니다.") String bio) {

  public ProfileUpdateCommand toCommand(Long userId) {
    return new ProfileUpdateCommand(userId, nickname, bio);
  }
}
