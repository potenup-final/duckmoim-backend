package com.duckmoim.chat.service;

/**
 * 한 주기(또는 한 청크 · 한 방)의 파기 결과 (CH-19).
 *
 * <p><b>집은 수와 파기한 수를 따로 센다.</b> 배치가 「더 있나」를 <b>집은 수</b>로 판정해야 해서다 — 파기한 수로 끊으면 저장소 삭제가 실패한 주기에 아직
 * 남은 방을 두고 배치가 끝난다 ({@code ChatImageCleanup} · {@code NotificationExpiry} 가 같은 이유로 같은 모양이다).
 *
 * <p>둘이 갈리는 것 자체가 관측 신호다. 인스턴스 역할에 {@code s3:DeleteObject} 가 없으면 정확히 「집기는 하는데 파기는 0」인 모양이 된다.
 *
 * @param picked 대상으로 집은 방 수
 * @param purged 파기 완료를 못박은 방 수
 * @param messages 지운 메시지 수
 * @param images 저장소와 표에서 지운 사진 수
 */
public record ChatPurge(int picked, int purged, int messages, int images) {

  public static ChatPurge empty() {
    return new ChatPurge(0, 0, 0, 0);
  }

  public ChatPurge plus(ChatPurge other) {
    return new ChatPurge(
        picked + other.picked,
        purged + other.purged,
        messages + other.messages,
        images + other.images);
  }
}
