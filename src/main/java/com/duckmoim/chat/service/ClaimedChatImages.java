package com.duckmoim.chat.service;

import java.util.List;

/**
 * 한 청크에서 배치가 지우기로 못박은 사진들 (CH-17).
 *
 * <p><b>집은 수와 못박은 목록을 따로 준다.</b> 배치가 「더 있나」를 <b>집은 수</b>로 판정해야 해서다 — 못박은 수로 끊으면, 후보 대부분을 전송이 먼저 가져간
 * 청크에서 아직 남은 고아를 두고 배치가 끝난다 ({@code NotificationExpiry} 가 같은 이유로 같은 모양이다).
 *
 * @param picked 후보로 읽은 행 수. 상한보다 적으면 더 없다
 * @param claimed {@code DELETING} 으로 못박힌 것. 이것만 S3 에서 지운다
 */
public record ClaimedChatImages(int picked, List<ClaimedChatImage> claimed) {

  /**
   * 지울 사진 하나.
   *
   * <p>엔티티를 service 밖으로 내보내지 않기 위한 값이다. 배치는 트랜잭션 밖에서 S3 를 부르므로 영속성 컨텍스트가 없고, 필요한 것도 이 둘뿐이다.
   */
  public record ClaimedChatImage(Long imageId, String objectKey) {}
}
