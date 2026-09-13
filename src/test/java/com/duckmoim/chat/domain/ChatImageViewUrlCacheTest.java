package com.duckmoim.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 살아 있는 서명을 다시 쓰는 규칙 (CH-15).
 *
 * <p><b>시계를 직접 민다.</b> 재사용 여부가 「남은 수명」으로만 갈리므로, 기다리지 않고 시각만 옮겨 경계를 본다.
 *
 * <p>저장소는 부를 때마다 다른 주소를 주는 가짜다 — <b>실제 S3 서명이 그렇기 때문이고</b>, 그 성질이 곧 이 캐시가 있는 이유다. 같은 주소가 나오면 그것은
 * 캐시가 한 일이지 저장소가 한 일이 아니다.
 */
@DisplayName("채팅 이미지 서명 캐시")
class ChatImageViewUrlCacheTest {

  private static final Duration TTL = Duration.ofMinutes(5);

  private final TickingClock clock = new TickingClock(Instant.parse("2026-10-02T11:00:00Z"));
  private final CountingStorage storage = new CountingStorage();
  private final ChatImageViewUrlCache cache = new ChatImageViewUrlCache(storage, TTL, clock);

  /** 캐시가 없으면 여기서 주소가 갈라지고, 브라우저가 같은 사진을 두 번 받는다. */
  @DisplayName("같은 사진을 연달아 열면 같은 주소가 나온다.")
  @Test
  void urlOf_reusesLiveSignature() {
    String first = cache.urlOf("chat/3/a.jpg").url();

    String second = cache.urlOf("chat/3/a.jpg").url();

    assertThat(second).isEqualTo(first);
    assertThat(storage.signCount()).isEqualTo(1);
  }

  /** 사진마다 서명이 따로다. 키를 안 보고 하나만 들고 있으면 남의 사진 주소가 나간다. */
  @DisplayName("다른 사진은 다른 주소를 받는다.")
  @Test
  void urlOf_signsEachObjectKey() {
    String first = cache.urlOf("chat/3/a.jpg").url();

    String second = cache.urlOf("chat/3/b.jpg").url();

    assertThat(second).isNotEqualTo(first);
  }

  /** 절반까지는 재사용한다 — 건네받은 쪽에 아직 절반이 남아 있다. */
  @DisplayName("남은 수명이 절반이면 아직 다시 쓴다.")
  @Test
  void urlOf_reusesUntilHalfLife() {
    String first = cache.urlOf("chat/3/a.jpg").url();
    clock.tick(Duration.ofMinutes(2).plusSeconds(30));

    String second = cache.urlOf("chat/3/a.jpg").url();

    assertThat(second).isEqualTo(first);
  }

  /**
   * <b>만료 직전의 주소를 건네지 않는다.</b>
   *
   * <p>재사용 창이 수명 전체였다면 여기서 4분 59초짜리 주소를 건네고, 화면은 그것을 받자마자 만료된 주소로 사진을 그리려다 실패한다.
   */
  @DisplayName("남은 수명이 절반 밑이면 새로 발급한다.")
  @Test
  void urlOf_resignsBelowHalfLife() {
    String first = cache.urlOf("chat/3/a.jpg").url();
    clock.tick(Duration.ofMinutes(2).plusSeconds(31));

    String second = cache.urlOf("chat/3/a.jpg").url();

    assertThat(second).isNotEqualTo(first);
    assertThat(storage.signCount()).isEqualTo(2);
  }

  /** 재사용한 주소는 이미 얼마간 쓰인 것이라 수명이 설정값과 다르다. 화면은 이 값으로 다시 물어볼 때를 정한다. */
  @DisplayName("다시 쓴 주소는 남은 수명만큼만 남았다고 답한다.")
  @Test
  void urlOf_reportsRemainingLifetime() {
    cache.urlOf("chat/3/a.jpg");
    clock.tick(Duration.ofMinutes(2));

    SignedChatImageUrl reused = cache.urlOf("chat/3/a.jpg");

    assertThat(reused.remaining()).isEqualTo(Duration.ofMinutes(3));
  }

  /**
   * 캐시가 가득 차도 발급이 멈추지 않는다.
   *
   * <p><b>상한을 넘으면 통째로 비운다.</b> 잃어버린 항목은 다음 요청이 다시 만든다 — 정본은 S3 의 객체이고 여기 있는 것은 다시 계산할 수 있는 값뿐이다. 이
   * 검사가 없으면 힙이 차는 날에야 알게 된다.
   */
  @DisplayName("담아 둔 주소가 상한을 넘어도 계속 발급한다.")
  @Test
  void urlOf_staysBoundedWhenFull() {
    for (int i = 0; i < 10_001; i++) {
      cache.urlOf("chat/3/" + i + ".jpg");
    }

    SignedChatImageUrl issued = cache.urlOf("chat/3/last.jpg");

    assertThat(issued.url()).isNotBlank();
    assertThat(issued.remaining()).isEqualTo(TTL);
  }

  /** 부를 때마다 다른 주소를 주는 가짜. 실제 서명이 그렇다 — 발급 시각이 서명에 들어간다. */
  private static class CountingStorage implements ChatImageStorage {

    private final AtomicInteger signCount = new AtomicInteger();

    int signCount() {
      return signCount.get();
    }

    @Override
    public String presignView(String objectKey, Duration ttl) {
      return "https://s3.test/" + objectKey + "?sig=" + signCount.incrementAndGet();
    }

    @Override
    public String presignUpload(String objectKey, String contentType, long contentLength) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<UploadedChatImage> findUploaded(String objectKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean delete(String objectKey) {
      throw new UnsupportedOperationException();
    }
  }

  /** 원하는 만큼 앞으로 미는 시계. 경계를 보려고 5분을 실제로 기다릴 수는 없다. */
  private static class TickingClock extends Clock {

    private Instant now;

    private TickingClock(Instant now) {
      this.now = now;
    }

    void tick(Duration elapsed) {
      now = now.plus(elapsed);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
