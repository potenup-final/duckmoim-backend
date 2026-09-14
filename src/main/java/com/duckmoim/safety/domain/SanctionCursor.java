package com.duckmoim.safety.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 백오피스 제재 목록의 커서 (AD-10).
 *
 * <p>정렬 키 {@code (expiresAt, id)} 를 담는다. <b>만료 임박순이다</b> — API-설계.md 「3. 커서 정의」의 표가 「제재 (백오피스) = 만료
 * 임박순」이라고 적었다. 신고 목록과 방향이 반대라 이어 읽는 부등호도 반대다.
 *
 * <p><b>정렬 축이 둘이다.</b> 만료가 비어 있는지가 먼저이고 ({@code AGE_HOLD} · {@code BANNED}) 만료 시각이 그다음이다. 스스로 풀리지
 * 않는 제재는 비교할 값이 없어 뒤로 보낸다 — 「끝이 없는 것이 가장 임박하다」로 읽히면 목록 첫 화면이 영구 정지로 찬다.
 *
 * <p><b>그래서 {@code expiresAt} 이 비어 있는 것도 커서에 담는다.</b> 안 담고 「커서가 없으면 첫 페이지」로 읽으면, 만료 없는 구간에 들어선 순간
 * 이어 읽기가 처음으로 돌아가 같은 페이지를 무한히 준다.
 *
 * <p><b>다른 커서 타입을 돌려 쓰지 않는다</b> (STAR-59). 값의 모양이 {@code ReportCursor} 와 같아 재사용하고 싶어지는 자리인데, 이어 읽는
 * 부등호가 뒤집히면 <b>판독은 되고 페이지 경계만 조용히 어긋난다.</b>
 *
 * <p>{@code id} 가 같이 들어가는 이유 — {@code expiresAt} 은 중복이 생긴다. 같은 날 같은 기간으로 정지된 둘이 페이지 경계에 걸리면 정렬이
 * 불안정해져 누락·중복이 난다.
 *
 * <p>{@code expiresAt} 은 저장된 값 그대로 UTC 다. 커서는 클라이언트에게 불투명하므로 KST 로 바꿔 담을 이유가 없고, 바꾸면 DB 비교값과 어긋난다.
 *
 * <p>Base64 는 암호가 아니라 <b>구조를 감춰 클라이언트가 값을 해석하고 의존하는 것을 막는</b> 장치다 (API 컨벤션).
 *
 * @param expiresAt 스스로 풀리지 않는 제재를 가리키면 null 이다
 */
public record SanctionCursor(LocalDateTime expiresAt, Long id) {

  private static final String DELIMITER = "|";

  /** 만료가 비어 있는 자리. 빈 조각으로 적어 「두 조각이어야 한다」는 검사를 그대로 둔다. */
  private static final String NO_EXPIRY = "";

  public SanctionCursor {
    if (id == null) {
      throw new IllegalArgumentException("커서는 id 를 갖는다");
    }
  }

  /** 형식이 어긋나면 {@link IllegalArgumentException} 이다 — 상위가 INVALID_INPUT 400 으로 옮긴다. */
  public static SanctionCursor decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("커서가 비어 있다");
    }

    String plain;
    try {
      plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("커서를 판독할 수 없다", e);
    }

    // limit 을 -1 로 두어야 "|12" 처럼 앞이 빈 입력과 "2026-09-04T01:00|" 처럼 뒤가 빈 입력이
    // 한 조각으로 줄지 않는다. 앞이 비는 것은 만료 없는 제재라 실제로 나온다.
    String[] parts = plain.split("\\" + DELIMITER, -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("커서 형식이 어긋난다");
    }

    try {
      return new SanctionCursor(expiry(parts[0]), Long.parseLong(parts[1]));
    } catch (DateTimeParseException | NumberFormatException e) {
      throw new IllegalArgumentException("커서 값이 어긋난다", e);
    }
  }

  public String encode() {
    String plain = (expiresAt == null ? NO_EXPIRY : expiresAt.toString()) + DELIMITER + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  /** 만료 없는 제재를 가리키는 커서인가. 이어 읽는 조건이 여기서 갈린다. */
  public boolean hasExpiry() {
    return expiresAt != null;
  }

  private static LocalDateTime expiry(String part) {
    return part.isEmpty() ? null : LocalDateTime.parse(part);
  }
}
