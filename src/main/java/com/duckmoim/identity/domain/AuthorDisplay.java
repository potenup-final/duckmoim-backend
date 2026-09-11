package com.duckmoim.identity.domain;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 응답의 작성자 블록에 실릴 세 값 (AU-11 「닉네임 익명화」).
 *
 * <p><b>탈퇴한 회원을 익명화하는 유일한 자리다.</b> 작성자 블록은 모집글 목록 · 모집글 상세 · 유저가 쓴 모집글 · 댓글 목록 · 백오피스 댓글 다섯 경로로
 * 나가는데, 익명화를 경로마다 적으면 하나를 빠뜨리는 날 그 경로로만 실명이 샌다. 도메인-모델링.md 「7.1 가시성과 권한」이 비밀 댓글 본문에 대해 <i>"판정 지점을
 * 하나로 모은다"</i> 고 정한 것과 같은 이유다.
 *
 * <p><b>DB 에 박지 않고 여기서 만든다.</b> {@link User#withdraw} 가 이미 그 길을 기각했다 — {@code uk_user_nickname} 이
 * UNIQUE 라 고정 문자열을 넣으면 <b>두 번째 탈퇴자에서 제약 위반</b>이다. 같은 메서드가 적은 「익명 문구는 화면 문구다」 는 <b>DB 에 박는 것</b>에 대한
 * 판단이고, 응답 조립은 마이그레이션 없이 바뀌므로 그 논거에 걸리지 않는다.
 *
 * <p><b>{@code nickname} 이 {@code null} 인지로 탈퇴를 판별하지 않는다.</b> 지금은 그것으로도 맞는다 — {@code
 * PENDING_SIGNUP_INFO} 계정은 쓰기에 도달할 수 없어 (I-02) 작성자로 등장하지 않는다. 그러나 두 상태가 같은 신호를 공유하면 <b>가입 축에 상태가 하나
 * 늘어나는 날 조용히 갈라진다.</b> 그래서 {@link SignupStatus} 를 받는다.
 *
 * <p><b>{@code Clock} 을 주입받지 않고 파라미터로 받는다.</b> domain 은 프레임워크에 묶이지 않는다 ({@link LastSeen} 과 같다).
 *
 * @param nickname 탈퇴했으면 {@link #WITHDRAWN_NICKNAME} 이다. 그 밖에는 저장된 값 그대로이며, 가입을 마치지 않은 계정은 {@code
 *     null} 일 수 있다
 * @param profileImageUrl 탈퇴했으면 {@code null} 이다
 * @param lastSeen 구간 값. 탈퇴했으면 {@code null} 이다
 */
public record AuthorDisplay(String nickname, String profileImageUrl, LastSeen lastSeen) {

  /**
   * 탈퇴한 회원의 자리표시자.
   *
   * <p><b>서버가 정한 값을 내린다.</b> 프론트가 {@code null} 을 보고 알아서 그리게 두지 않는다 — API-설계.md 「2-5. 댓글
   * (Companion)」이 작성자 블록을 {@code id} · {@code nickname} · {@code profileImageUrl} · {@code
   * lastSeen} 넷으로 못박아 플래그를 더할 자리가 없고, 화면-계약.md 에 탈퇴 표시 문구가 정의돼 있지 않아 프론트가 그릴 근거도 없다.
   */
  public static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

  /**
   * 저장된 작성자 값을 응답에 나갈 모양으로 줄인다.
   *
   * <p><b>탈퇴하면 셋을 한꺼번에 끊는다.</b> 닉네임만 가리고 사진을 남기면 익명화가 성립하지 않고 (사진이 이름보다 더 식별적이다), {@code lastSeen}
   * 을 남기면 <b>「탈퇴한 회원 · 오늘 접속」</b> 이 뜬다 — {@code lastSeenAt} 이 탈퇴 시점 값으로 남아 있어 탈퇴 직후에는 최신 구간으로 계산되기
   * 때문이다.
   *
   * <p>사진과 접속 시각은 {@link User#withdraw} 가 지우거나 갱신을 멈춰 사실상 이미 비어 있지만, <b>여기서 다시 끊는 것이 값싸고 여기가 계약을
   * 정하는 자리다.</b> 저장 쪽이 어떻게 바뀌어도 나가는 모양은 이 메서드가 보장한다.
   *
   * <p>저장된 {@code lastSeenAt} 컬럼을 비우지는 않는다. 파기 범위는 처리방침이 정할 일이고, {@link User#withdraw} 가 {@code
   * bio} · {@code birthYear} 를 건드리지 않은 것과 같은 판단이다.
   *
   * @param lastSeenAtUtc 저장된 값. 관측된 적이 없으면 {@code null} 이다 (AU-03 이 재발급 시점에만 갱신한다)
   */
  public static AuthorDisplay of(
      SignupStatus status,
      String nickname,
      String profileImageUrl,
      LocalDateTime lastSeenAtUtc,
      Clock clock) {

    if (status == SignupStatus.WITHDRAWN) {
      return new AuthorDisplay(WITHDRAWN_NICKNAME, null, null);
    }

    return new AuthorDisplay(nickname, profileImageUrl, LastSeen.from(lastSeenAtUtc, clock));
  }
}
