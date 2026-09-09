package com.duckmoim.identity.presentation.dto;

import com.duckmoim.identity.domain.LastSeen;
import com.duckmoim.identity.service.MyProfile;
import com.duckmoim.identity.service.SanctionView;
import java.time.OffsetDateTime;

/**
 * 내 정보 응답 (API-설계.md 2-2).
 *
 * <p>담기는 것이 넷이라고 정본이 못박았다 — 프로필 · {@code signupCompleted} · {@code lastSeen}(구간 값) · {@code
 * sanction}. <b>{@code lastSeenAt} 원본과 {@code birthYear} 는 담지 않는다.</b>
 *
 * <p>null 이 될 수 있는 필드를 생략하지 않고 null 로 명시한다 (API 컨벤션). 가입 미완료 계정은 닉네임이 없고, 접속이 관측된 적 없으면 {@code
 * lastSeen} 도 없다.
 */
public record MyProfileResponse(
    Long id,
    String nickname,
    String profileImageUrl,
    String bio,
    boolean signupCompleted,
    LastSeen lastSeen,
    Sanction sanction) {

  /**
   * 제재 상태 (AU-12 · 화면-계약.md 「제재 상태」).
   *
   * <p>제재가 없으면 {@code kind} 가 {@code NONE} 이다. <b>키를 빼지 않는 이유</b> — 정본이 이미 계약으로 적어 두었고, 빼면 클라이언트가
   * 제재 상태에 따라 키가 생겼다 없어졌다 하는 응답을 다뤄야 한다.
   *
   * <p>값은 {@code SanctionReader} 포트로 들어온다. 한때 「제재 없음」 고정이었고 STAR-80 이 실구현을 꽂았다.
   */
  public record Sanction(
      String kind, String reason, OffsetDateTime until, OffsetDateTime issuedAt) {

    private static Sanction from(SanctionView view) {
      return new Sanction(view.kind(), view.reason(), view.until(), view.issuedAt());
    }
  }

  public static MyProfileResponse from(MyProfile profile) {
    return new MyProfileResponse(
        profile.id(),
        profile.nickname(),
        profile.profileImageUrl(),
        profile.bio(),
        profile.signupCompleted(),
        profile.lastSeen(),
        Sanction.from(profile.sanction()));
  }
}
