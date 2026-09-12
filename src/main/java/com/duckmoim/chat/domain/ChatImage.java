package com.duckmoim.chat.domain;

import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방에 올린 사진 한 장 (CH-14 · CH-17).
 *
 * <p><b>바이트를 들고 있지 않다.</b> 브라우저가 S3 로 직접 올려서 파일이 서버를 지나지 않는다 — 이 엔티티가 아는 것은 <b>어디에 있는가</b>(객체 키)와
 * <b>어디까지 왔는가</b>({@link ChatImageStatus})다.
 *
 * <p><b>{@code Message} 밖의 애그리게이트다.</b> 메시지보다 먼저 생기고(업로드가 전송보다 앞이다) 메시지 없이도 존재하기 때문이다 — 그 「메시지 없이
 * 남은 것」이 {@code CH-17} 이 지우는 대상이다. 애그리게이트가 같으면 전송 전의 사진을 담을 곳이 없다.
 *
 * <p><b>그래서 {@code roomId} 와 {@code uploaderId} 를 직접 들고 있다.</b> 확정과 전송이 「이 방의 멤버인가」 · 「올린 사람이 맞는가」를
 * 물어야 하고, 메시지가 아직 없으므로 그쪽으로 물어볼 수 없다.
 *
 * <p><b>공개 주소를 만들 수 없는 것이 의도다.</b> {@code CH-15} 가 「공개 주소를 쓰지 않는다」로 정했고 그것은 별 티켓이라, 이 엔티티는 키까지만 알고
 * 주소를 만드는 메서드를 갖지 않는다 — {@code User#profileImageUrl} 이 주소를 저장하는 것과 갈리는 자리다 (계획서 8.2).
 */
@Entity
@Table(name = "chat_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatImage extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 애그리게이트 밖은 ID 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」).
  @Column(name = "room_id", nullable = false)
  private Long roomId;

  @Column(name = "uploader_id", nullable = false)
  private Long uploaderId;

  /** S3 객체 키. 이 값이 이 엔티티의 정체다 — 표의 유니크 제약이 한 객체에 한 행을 보장한다. */
  @Column(name = "object_key", nullable = false, length = 255)
  private String objectKey;

  /** 발급 때는 선언한 값, 확정 때는 실제 값으로 덮인다. */
  @Column(name = "content_type", nullable = false, length = 50)
  private String contentType;

  /** 확정 전에는 {@code 0} 이다. 선언한 크기는 검사에만 쓰고 저장하지 않는다. */
  @Column(name = "byte_size", nullable = false)
  private long byteSize;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ChatImageStatus status;

  private ChatImage(Long roomId, Long uploaderId, String objectKey, String contentType) {
    this.roomId = roomId;
    this.uploaderId = uploaderId;
    this.objectKey = objectKey;
    this.contentType = contentType;
    this.byteSize = 0L;
    this.status = ChatImageStatus.PENDING;
  }

  /**
   * 서명을 발급하면서 자리를 잡는다 (CH-14).
   *
   * <p><b>올라오기 전에 행이 생긴다.</b> 이것이 {@code CH-17} 의 전제다 — 올리다 만 객체도 행이 있어야 배치가 찾아 지운다. 행 없이 S3 에만 남은
   * 객체는 아무도 모른다.
   */
  public static ChatImage pending(
      Long roomId, Long uploaderId, String objectKey, String contentType) {

    return new ChatImage(roomId, uploaderId, objectKey, contentType);
  }

  /**
   * 저장소에서 읽은 실제 값으로 확정한다 (CH-14).
   *
   * <p><b>선언한 값을 실제 값으로 덮는다.</b> 둘을 다 남기지 않는 것은, 남기면 「어느 쪽으로 판정했는지」가 읽는 자리마다 갈리기 때문이다. 판정은 확정 시점에
   * 끝났고 그 결과가 이 행이다.
   *
   * <p><b>두 번 확정해도 같은 결과다.</b> 클라이언트가 확정 요청을 재시도하는 것이 정상 경로다 — 응답을 못 받았을 때 다시 부른다.
   *
   * @throws BusinessException 이미 메시지에 실렸으면 {@code CHAT_IMAGE_NOT_UPLOADED} — 그 시점에는 바꿀 것이 없고, 「없다」로
   *     답해 존재를 알려주지 않는다
   */
  public void confirm(String actualContentType, long actualByteSize) {
    if (status == ChatImageStatus.ATTACHED) {
      throw new BusinessException(ChatErrorCode.CHAT_IMAGE_NOT_UPLOADED);
    }

    this.contentType = actualContentType;
    this.byteSize = actualByteSize;
    this.status = ChatImageStatus.CONFIRMED;
  }

  /**
   * 메시지에 싣는다 (CH-14).
   *
   * <p><b>여기가 검증 기준 「업로드 확인 전 메시지 전송 시 400」이 성립하는 자리다.</b> {@code PENDING} 이면 S3 에 올라왔는지조차 모르는
   * 상태이고, {@code ATTACHED} 면 이미 다른 메시지가 쓴 사진이다.
   *
   * <p><b>셋을 한 코드로 답한다</b> — 없음 · 확인 전 · 이미 쓴 것. 갈라서 답하면 「그 번호의 사진이 존재하며 남이 이미 썼다」는 사실을 알려준다.
   *
   * @throws BusinessException 확정 상태가 아니면 {@code CHAT_IMAGE_NOT_CONFIRMED}
   */
  public void attach() {
    if (status != ChatImageStatus.CONFIRMED) {
      throw new BusinessException(ChatErrorCode.CHAT_IMAGE_NOT_CONFIRMED);
    }

    this.status = ChatImageStatus.ATTACHED;
  }

  /** 그 방에 그 사람이 올린 것인가. 확정과 전송이 같은 질문을 한다. */
  public boolean isUploadedBy(Long roomId, Long uploaderId) {
    return this.roomId.equals(roomId) && this.uploaderId.equals(uploaderId);
  }

  /** 배치가 지워도 되는가 (CH-17). 메시지에 실린 것은 건드리지 않는다. */
  public boolean isOrphan() {
    return status != ChatImageStatus.ATTACHED;
  }
}
