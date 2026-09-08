package com.duckmoim.identity.domain;

/**
 * 프로필 수정 입력의 모양 (AU-08).
 *
 * <p><b>영속 {@code @Embeddable} 이 아니다.</b> 도메인 1장이 닉네임을 {@code SignupInfo} 와 {@code Profile}
 * <b>양쪽에</b> 적어 두었는데 컬럼은 하나다. 둘 다 embeddable 로 만들면 같은 {@code nickname} 을 두 번 매핑하게 된다. 그래서 닉네임 컬럼은
 * {@link User} 가 직접 들고, 이 값 객체는 <b>입력의 모양으로만</b> 산다 — C 티켓이 {@code SignupInfo} 에서 내린 판단과 대칭이다.
 *
 * <p><b>{@code null} 은 「없음」이 아니라 「안 건드림」이다.</b> {@code PATCH} 는 부분 수정이라 「보내지 않았다」와 「비워 달라」가 달라야 한다.
 * 세 상태를 이렇게 구분한다.
 *
 * <table border="1">
 *   <caption>필드 값의 뜻</caption>
 *   <tr><th>값</th><th>뜻</th></tr>
 *   <tr><td>{@code null}</td><td>안 건드린다</td></tr>
 *   <tr><td>빈 문자열</td><td><b>비운다</b> — {@code bio} 만 허용된다</td></tr>
 *   <tr><td>그 밖</td><td>그 값으로 바꾼다</td></tr>
 * </table>
 *
 * <p>API 컨벤션의 <i>"null 가능 필드는 생략하지 않고 null 로 명시한다"</i> 는 <b>응답 규칙</b>이고 요청은 정해 두지 않았다. 그래서 여기서 정하고
 * PR 에 근거를 남긴다.
 *
 * <p><b>출생연도와 이미지 URL 이 없다.</b> 출생연도는 가입 후 잠기고(API 설계 2-2 가 <i>"받지 않는다"</i>로 못박았다), 이미지 URL 은 업로드
 * 경로(AU-08 · I 티켓)가 채운다. 필드를 두고 400 을 내면 <b>그 필드가 언젠가 열릴 것처럼 보인다</b> — 두지 않으면 계약에 없다.
 */
public record Profile(String nickname, String bio) {}
