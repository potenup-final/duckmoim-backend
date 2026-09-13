package com.duckmoim.chat.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 아직 살아 있는 서명을 다시 쓴다 (CH-15).
 *
 * <p><b>서명에 발급 시각이 들어가 부를 때마다 주소가 달라진다.</b> 그대로 두면 같은 사진이 목록을 새로 그릴 때마다 새 주소가 되고, <b>브라우저 캐시가 매번
 * 빗나가 같은 바이트를 다시 받는다</b> — 대화 사진은 스크롤을 오르내리며 몇 번이고 다시 그려지는 자원이다.
 *
 * <pre>
 * 캐시 없음   목록 3회 조회 → 주소 3개 → S3 GET 3회 (같은 사진)
 * 캐시 있음   목록 3회 조회 → 주소 1개 → S3 GET 1회 + 브라우저 캐시 2회
 * </pre>
 *
 * <p><b>서명은 사람이 아니라 객체에 걸린다.</b> 같은 방 멤버 둘이 같은 주소를 받아도 문제가 없다 — 볼 자격이 있는지는 이 캐시에 닿기 전에 이미 끝났다. 그래서
 * 키가 객체 키 하나이고, 그래서 캐시를 공유할 수 있다.
 *
 * <p><b>Redis 가 아니라 인스턴스 안에 둔다.</b> 서명은 네트워크 호출이 아니라 로컬 계산이라 Redis 왕복이 오히려 비싸고, Redis 는 죽어도 되는
 * 부속이라(STAR-110) 사진 열람이 거기 걸리면 안 된다. 대가는 인스턴스가 둘이라 주소가 둘이 되는 것인데, <b>매 요청 새 주소</b>였던 것과 견주면 그대로
 * 이득이다.
 *
 * <p><b>{@code ChatImagePolicy} 와 같은 자리에 있다</b> — 프레임워크가 안 들어와 domain 에 두고, service 가 {@code new} 로
 * 쥔다. 그 서비스가 싱글턴이라 캐시도 하나다.
 *
 * <h2>남은 수명이 절반 밑이면 새로 발급한다</h2>
 *
 * <p>만료 직전의 주소를 건네면 화면이 그것을 받자마자 만료된다. 재사용 창을 수명의 절반으로 잡아 <b>건네진 주소는 언제나 절반 이상 남아 있다.</b>
 *
 * <pre>
 * 0분 ─────────── 2분 30초 ─────────── 5분
 * │  재사용한다      │  새로 발급한다      │ 만료
 * </pre>
 */
public class ChatImageViewUrlCache {

  /**
   * 담아 둘 주소의 최대 개수.
   *
   * <p><b>상한이 없으면 활동이 많은 날 힙에 계속 쌓인다.</b> 한 항목이 URL 하나(1KB 안팎)라 이 값은 10MB 안쪽이고, EC2 한 대 구성에서 감당할 수
   * 있는 폭이다. 서명 수명이 분 단위라 실제로는 그보다 훨씬 적게 찬다.
   */
  private static final int MAX_ENTRIES = 10_000;

  /** 재사용 창. 수명의 이 비율만큼 남아 있어야 다시 쓴다. */
  private static final int REUSE_DIVISOR = 2;

  private final ChatImageStorage storage;
  private final Duration ttl;
  private final Clock clock;

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  public ChatImageViewUrlCache(ChatImageStorage storage, Duration ttl, Clock clock) {
    this.storage = storage;
    this.ttl = ttl;
    this.clock = clock;
  }

  /**
   * 그 객체를 볼 수 있는 주소. 살아 있는 것이 있으면 그것을, 없으면 새로 발급한다.
   *
   * <p><b>발급을 {@code compute} 안에서 한다.</b> 같은 사진을 여럿이 동시에 열 때 (방금 올라온 사진이 그렇다) 각자 발급하면 주소가 그만큼 갈라져
   * 캐시를 둔 뜻이 사라진다. 서명이 네트워크가 아니라 로컬 계산이라 그 잠금이 짧다.
   *
   * <p><b>권한 판정이 여기 없다.</b> 부르는 쪽이 이미 물었다 — 이 메서드는 「이 키의 주소를 달라」만 안다.
   */
  public SignedChatImageUrl urlOf(String objectKey) {
    Instant now = clock.instant();

    if (entries.size() >= MAX_ENTRIES) {
      evict(now);
    }

    Entry entry =
        entries.compute(
            objectKey,
            (key, cached) ->
                cached != null && cached.isReusableAt(now, ttl) ? cached : sign(key, now));

    return new SignedChatImageUrl(entry.url(), Duration.between(now, entry.expiresAt()));
  }

  private Entry sign(String objectKey, Instant now) {
    return new Entry(storage.presignView(objectKey, ttl), now.plus(ttl));
  }

  /**
   * 상한에 닿으면 만료된 것부터 버리고, 그래도 차 있으면 통째로 비운다.
   *
   * <p><b>비워도 되는 것이 이 캐시의 성질이다.</b> 잃어버린 항목은 다음 요청이 다시 만든다 — 정본은 S3 의 객체이고 여기 있는 것은 <b>다시 계산할 수 있는
   * 값</b>뿐이다. 그래서 LRU 같은 장치를 두지 않는다. 정교한 퇴거 규칙의 값보다 그것을 유지하는 비용이 크다.
   */
  private void evict(Instant now) {
    entries.values().removeIf(entry -> entry.isExpiredAt(now));

    if (entries.size() >= MAX_ENTRIES) {
      entries.clear();
    }
  }

  /**
   * 담아 둔 주소 하나.
   *
   * <p>만료 시각을 들고 있는 것은 <b>재사용한 주소의 남은 수명</b>을 세기 위해서다 — 발급 시각만 알면 부르는 쪽이 TTL 을 또 알아야 한다.
   */
  private record Entry(String url, Instant expiresAt) {

    boolean isReusableAt(Instant now, Duration ttl) {
      return Duration.between(now, expiresAt).compareTo(ttl.dividedBy(REUSE_DIVISOR)) >= 0;
    }

    boolean isExpiredAt(Instant now) {
      return !now.isBefore(expiresAt);
    }
  }
}
