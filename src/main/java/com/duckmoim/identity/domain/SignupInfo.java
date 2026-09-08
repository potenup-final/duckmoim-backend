package com.duckmoim.identity.domain;

import java.util.Objects;

/**
 * 로그인 이후 추가로 입력받는 가입 정보 — 닉네임과 출생연도 (도메인-모델링.md 「1. 유비쿼터스 언어」).
 *
 * <p><b>둘이 함께 들어와야 한다.</b> 하나만 채운 상태를 허용하면 「가입을 마쳤는가」의 답이 둘로 갈린다 — 닉네임은 있는데 출생연도가 없는 계정이 생기고, I-15
 * 를 통과한 적 없는 사람이 활동하게 된다. 그래서 {@link User#completeSignup} 이 이 객체 하나만 받는다.
 *
 * <p><b>영속 {@code @Embeddable} 이 아니다.</b> 도메인 1장이 닉네임을 {@code SignupInfo} 와 {@code Profile}
 * <b>양쪽</b>에 적어 두었는데, 컬럼은 하나뿐이라 둘 다 embeddable 로 만들면 같은 {@code nickname} 을 두 번 매핑하게 된다. 그래서 <b>닉네임
 * 컬럼은 {@code User} 가 직접 들고</b> 이 값 객체는 입력의 모양으로만 산다 — 유니크 제약과 {@code existsByNickname} 조회도 그 편이
 * 단순하다. {@code Profile}(AU-08)을 만들 때 같은 판단이 필요하다.
 */
public record SignupInfo(String nickname, BirthYear birthYear) {

  public SignupInfo {
    Objects.requireNonNull(nickname, "가입 정보는 닉네임을 가진다.");
    Objects.requireNonNull(birthYear, "가입 정보는 출생연도를 가진다.");
  }

  /**
   * 입력값에서 가입 정보를 만든다. 만 14세 미만이면 여기서 걸린다 (I-15).
   *
   * @param currentYear KST 기준 올해. 부르는 쪽이 {@code Clock} 으로 읽어 넘긴다
   */
  public static SignupInfo of(String nickname, int birthYear, int currentYear) {
    return new SignupInfo(nickname, BirthYear.of(birthYear, currentYear));
  }
}
