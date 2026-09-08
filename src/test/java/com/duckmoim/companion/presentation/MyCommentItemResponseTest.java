package com.duckmoim.companion.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 내 내역에서도 {@code I-07} 이 지켜지는지 본다 — <b>본문은 권한자 외에게 응답 payload 에 존재하지 않는다.</b>
 *
 * <p>{@link CommentItemResponseTest} 와 같은 것을 보는 이유는 <b>키 제거가 타입마다 따로 붙는 장치</b>이기 때문이다.
 * {@code @JsonInclude} 는 필드에 달려 있어서 새 응답 타입을 만들 때 빠뜨리면 다른 어떤 테스트도 잡지 못한다 — 조립기는 여전히 null 을 주고 컴파일도
 * 통과한다.
 *
 * <p>테스트 컨벤션이 <i>"응답에서 필드를 제거하는 요구사항은 값이 null 인지가 아니라 키가 없는지를 검증한다"</i> 고 정했다.
 *
 * <p><b>시각의 표기는 여기서 보지 않는다.</b> 날짜를 ISO-8601 로 쓸지 epoch 숫자로 쓸지는 Spring Boot 가 정하는 Jackson 설정이라, 맨
 * {@code ObjectMapper} 로 찍으면 실제 응답과 다른 것을 검증하게 된다 ({@link CommentItemResponseTest} 가 실측해 두었다). 응답
 * 문자열 형태는 컨트롤러 테스트가 본다.
 */
class MyCommentItemResponseTest {

  private static final OffsetDateTime WRITTEN_AT =
      OffsetDateTime.of(2026, 8, 30, 9, 40, 0, 0, ZoneOffset.ofHours(9));

  private final ObjectMapper objectMapper =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  @DisplayName("본문을 볼 수 없으면 content 키가 응답에서 사라진다.")
  @Test
  void contentKeyDisappears() throws Exception {
    String json = objectMapper.writeValueAsString(response(null));

    assertThat(objectMapper.readTree(json).has("content")).isFalse();
  }

  @DisplayName("본문을 볼 수 있으면 content 키가 나타난다.")
  @Test
  void contentKeyAppears() throws Exception {
    String json = objectMapper.writeValueAsString(response("카톡 아이디 night_ticket 입니다"));

    assertThat(objectMapper.readTree(json).get("content").asText())
        .isEqualTo("카톡 아이디 night_ticket 입니다");
  }

  /** 사라지는 것은 본문 하나뿐이다. 나머지는 키가 남아야 한다 (API-컨벤션.md 「필드 표기 규칙」). */
  @DisplayName("본문이 사라져도 모집글과 작성 시각은 키가 남는다.")
  @Test
  void otherKeysRemain() throws Exception {
    String json = objectMapper.writeValueAsString(response(null));

    assertThat(objectMapper.readTree(json).has("postId")).isTrue();
    assertThat(objectMapper.readTree(json).has("postTitle")).isTrue();
    assertThat(objectMapper.readTree(json).has("secret")).isTrue();
    assertThat(objectMapper.readTree(json).has("createdAt")).isTrue();
  }

  private static MyCommentItemResponse response(String content) {
    return new MyCommentItemResponse(31L, 1L, "에이티즈 팝업 오픈런 같이 하실 분", content, true, WRITTEN_AT);
  }
}
