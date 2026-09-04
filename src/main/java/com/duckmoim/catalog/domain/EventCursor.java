package com.duckmoim.catalog.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 행사 목록의 커서 (EV-06).
 *
 * <p>정렬 키 {@code (endsOn, id)} 를 그대로 담는다. 커서는 <b>순번이 아니라 위치</b>여서, 정적 페이지에 박힌 커서가 그 사이 데이터가 바뀌어도
 * 안전하다 (API 설계 2-3). OFFSET 이었다면 정적 페이지의 2페이지가 항목을 건너뛰거나 중복시킨다.
 *
 * <p>id 가 같이 들어가는 것이 EV-06 의 검증 기준 그 자체다. endsOn 만으로는 같은 날 끝나는 행사들 사이에 순서가 없어 페이지 경계에서 누락·중복이 생긴다.
 *
 * <p><b>정렬 키가 endsOn 인 이유</b> — 화면 계약이 마감 임박 순을 요구한다 (화면-계약/행사.md 9장). 프론트 {@code sortByDeadline} 과
 * 같은 축이다. 오늘 날짜에 따라 값이 변하는 {@code isOngoing} 파티션을 커서 키로 쓰지 않는 것이 중요하다 — 자정을 넘기면 커서가 가리키던 위치가 이동해
 * EV-06 이 금지한 누락·중복이 난다. 끝난 행사를 조회에서 아예 빼는 것으로 그 파티션을 대신한다.
 *
 * <p>클라이언트에게는 불투명한 문자열이다 (API 컨벤션). Base64 는 암호가 아니라 <b>구조를 감춰 클라이언트가 값을 해석하고 의존하는 것을 막는</b> 장치다.
 */
public record EventCursor(LocalDate endsOn, Long id) {

  private static final String DELIMITER = "|";

  public EventCursor {
    if (endsOn == null || id == null) {
      throw new IllegalArgumentException("커서는 endsOn 과 id 를 모두 갖는다");
    }
  }

  public static EventCursor of(LocalDate endsOn, Long id) {
    return new EventCursor(endsOn, id);
  }

  /** 형식이 어긋나면 {@link IllegalArgumentException} 이다 — 상위가 400 으로 옮긴다. */
  public static EventCursor decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("커서가 비어 있다");
    }

    String plain;
    try {
      plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("커서를 판독할 수 없다", e);
    }

    // limit 을 -1 로 두어야 "2026-09-14|" 처럼 뒤가 빈 입력이 조용히 한 조각으로 줄지 않는다.
    String[] parts = plain.split("\\" + DELIMITER, -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("커서 형식이 어긋난다");
    }

    try {
      return new EventCursor(LocalDate.parse(parts[0]), Long.parseLong(parts[1]));
    } catch (DateTimeParseException | NumberFormatException e) {
      throw new IllegalArgumentException("커서 값이 어긋난다", e);
    }
  }

  public String encode() {
    String plain = endsOn + DELIMITER + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }
}
