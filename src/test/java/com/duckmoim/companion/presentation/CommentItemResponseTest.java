package com.duckmoim.companion.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.CommentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code I-07} 이 실제로 지켜지는지 본다 — <b>비밀 댓글 본문은 권한자 외에게 응답 payload 에 존재하지 않는다.</b>
 *
 * <p>판정이 맞는지는 {@code CommentVisibilityPolicyTest} 가 보고, 여기서는 <b>판정 결과가 JSON 에서 어떻게 생기는지</b>를 본다.
 * 조립기가 본문을 null 로 두어도 직렬화가 키를 남기면 I-07 이 깨진다 — 그 구멍이 코드에서는 안 보이고 응답에서만 보인다.
 *
 * <p>테스트 컨벤션이 <i>"응답에서 필드를 제거하는 요구사항은 값이 null 인지가 아니라 키가 없는지를 검증한다"</i> 고 정했다.
 */
class CommentItemResponseTest {

  private static final LocalDateTime WRITTEN_AT_UTC = LocalDateTime.of(2026, 9, 14, 0, 0);

  private final ObjectMapper objectMapper =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  @DisplayName("본문을 볼 수 없으면 content 키가 응답에서 사라진다.")
  @Test
  void contentKeyDisappears() throws Exception {
    String json = objectMapper.writeValueAsString(response(null));

    assertThat(json).doesNotContain("content");
    assertThat(objectMapper.readTree(json).has("content")).isFalse();
  }

  @DisplayName("본문을 볼 수 있으면 content 키가 나타난다.")
  @Test
  void contentKeyAppears() throws Exception {
    String json = objectMapper.writeValueAsString(response("연락처 남길게요"));

    assertThat(objectMapper.readTree(json).get("content").asText()).isEqualTo("연락처 남길게요");
  }

  /**
   * 사라지는 것은 본문 하나뿐이다.
   *
   * <p>{@code @JsonInclude} 를 클래스에 걸었다면 {@code parentId} 의 null 까지 사라진다. API-컨벤션.md 「필드 표기 규칙」이
   * <i>"null 이 될 수 있는 필드는 응답에서 생략하지 않고 null 로 명시한다"</i> 고 정했고, 권한으로 감추는 필드만 예외다.
   */
  @DisplayName("루트 댓글의 parentId 는 null 로 명시되고 키가 남는다.")
  @Test
  void otherNullKeysRemain() throws Exception {
    String json = objectMapper.writeValueAsString(response(null));

    assertThat(objectMapper.readTree(json).has("parentId")).isTrue();
    assertThat(objectMapper.readTree(json).get("parentId").isNull()).isTrue();
  }

  /**
   * <b>JSON 문자열이 아니라 값으로 검증한다.</b> 날짜를 ISO-8601 로 쓸지 epoch 숫자로 쓸지는 Jackson 설정이고 Spring Boot 가 정한다 —
   * 여기서 맨 {@code ObjectMapper} 로 찍어 보면 실제 응답과 다른 것을 검증하게 된다 (실측했다. {@code 1.789344E9} 가 나온다). 응답
   * 문자열 형태는 컨트롤러 테스트가 본다.
   */
  @DisplayName("저장된 UTC 시각이 KST 오프셋으로 바뀐다.")
  @Test
  void toKstShiftsToSeoul() {
    OffsetDateTime kst = CommentItemResponse.toKst(WRITTEN_AT_UTC);

    assertThat(kst.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    assertThat(kst.getHour()).isEqualTo(9);
    assertThat(kst.toInstant()).isEqualTo(WRITTEN_AT_UTC.toInstant(ZoneOffset.UTC));
  }

  private static CommentItemResponse response(String content) {
    return new CommentItemResponse(
        12L,
        null,
        true,
        CommentStatus.ACTIVE,
        content,
        CommentItemResponse.toKst(WRITTEN_AT_UTC),
        new CommentAuthorResponse(3L, "밤샘예매", "/avatar/a2.webp"));
  }
}
