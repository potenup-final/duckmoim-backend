package com.duckmoim.identity.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 최근 접속을 다섯 구간으로 줄인 값 (도메인-모델링.md 「7.2 최근 접속일 노출」).
 *
 * <p><b>원본 시각을 응답에 내리지 않기 위해 있다.</b> 7.2 가 이유를 적어 두었다 — <i>"낯선 사람과 대면으로 만나는 서비스이고 1차에 차단이 없어, 정확한
 * 시각을 그대로 내리면 특정인의 활동 패턴이 추적된다."</i> 저장 필드 {@code lastSeenAt} 은 어느 응답에도 나가지 않고, 나가는 것은 이 구간뿐이다.
 *
 * <p><b>왜 identity 에 있나.</b> 이 값은 공개 프로필(AU-09) · {@code /users/me} · 모집글 작성자 · 댓글 작성자 넷에 나간다. 7.2
 * 가 <i>"본인 조회에서도 동일하게 구간으로 내린다. 경로마다 형태가 다르면 조립 지점이 갈라진다"</i> 고 정했으므로 회원 쪽에 한 번만 둔다. 댓글 목록(CM-06)이
 * 먼저 필요해져 여기서 만들었을 뿐이고, AU-09 담당이 이어서 쓴다.
 *
 * <p><b>domain 에 두는 것이 중요하다.</b> 게이트가 {@code presentation} 을 아무도 참조하지 못하게 막는다. 응답 DTO 안에 계산을 넣으면 다른
 * 컨텍스트가 못 쓰고 같은 계산이 하나 더 생긴다.
 */
public enum LastSeen {
  TODAY,
  WITHIN_3_DAYS,
  WITHIN_WEEK,
  WITHIN_MONTH,
  LONG_AGO;

  /**
   * 저장된 시각을 구간으로 줄인다.
   *
   * <p>경계는 7.2 의 표 그대로다 — 24시간 · 3일 · 7일 · 30일이며 <b>「이내」라서 경계값은 좁은 쪽에 든다.</b> 정확히 24시간 전이면 {@code
   * TODAY} 이고, 1초를 더 지나야 {@code WITHIN_3_DAYS} 다.
   *
   * <p><b>7.2 의 「구간 계산 기준은 KST」는 이 계산을 바꾸지 않는다.</b> 표의 조건이 달력 날짜가 아니라 경과 시간이라 시간대와 무관하게 같은 답이 나온다.
   * 「오늘 활동」이라는 화면 문구가 달력의 오늘로 읽히지만, 정본은 문구가 아니라 조건 열이다. 그래도 {@code Clock} 빈이 KST 라 둘이 어긋날 일은 없다.
   *
   * <p>{@code Clock} 을 주입받지 않고 파라미터로 받는다. domain 은 프레임워크에 묶이지 않는다.
   *
   * @param lastSeenAtUtc 저장된 값. <b>관측된 적이 없으면 null 이고, 그때는 null 을 돌려준다</b> — 로그인 후 토큰을 한 번도 재발급하지
   *     않은 계정이 그렇다 (AU-03 이 재발급 시점에만 갱신한다). 없는 것을 {@code LONG_AGO} 로 적으면 방금 가입한 사람이 한 달 넘게 활동이 없는
   *     것으로 보인다
   */
  public static LastSeen from(LocalDateTime lastSeenAtUtc, Clock clock) {
    if (lastSeenAtUtc == null) {
      return null;
    }

    Duration elapsed =
        Duration.between(lastSeenAtUtc.toInstant(ZoneOffset.UTC), Instant.now(clock));

    if (elapsed.compareTo(Duration.ofHours(24)) <= 0) {
      return TODAY;
    }
    if (elapsed.compareTo(Duration.ofDays(3)) <= 0) {
      return WITHIN_3_DAYS;
    }
    if (elapsed.compareTo(Duration.ofDays(7)) <= 0) {
      return WITHIN_WEEK;
    }
    if (elapsed.compareTo(Duration.ofDays(30)) <= 0) {
      return WITHIN_MONTH;
    }
    return LONG_AGO;
  }
}
