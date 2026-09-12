package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatImage;
import com.duckmoim.chat.domain.ChatImagePolicy;
import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.UploadedChatImage;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatImageRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅 이미지 업로드 (CH-14).
 *
 * <p><b>경로가 둘이다</b> — 서명된 주소를 <b>발급</b>하고, 올라간 것을 <b>확정</b>한다. 파일이 서버를 지나지 않아서 한 번으로 끝나지 않는다
 * ({@code ProfileImageService} 와 같은 구조이고, 명세가 「AU-08 과 같은 구조」로 정했다).
 *
 * <p><b>그쪽과 갈리는 것이 셋이다.</b>
 *
 * <pre>
 * ① 발급 때 DB 에 행을 만든다      CH-17 이 고아를 지워야 해서다. 행이 없으면 올리다 만 객체를 아무도 모른다
 * ② 방 멤버를 판정한다             프로필은 「내 것」이면 끝인데, 방은 여럿이 공유하는 자원이다
 * ③ 공개 주소를 만들지 않는다       CH-15 가 별 티켓이다 (계획서 8.2)
 * </pre>
 *
 * <p><b>멤버 판정을 {@link ChatRoomMembershipReader} 에게 맡긴다.</b> 같은 판정을 두 벌 두지 않는다 — 목록 조회 · 전송 · 스트림이
 * 쓰는 그 두 코드({@code CHAT_ROOM_NOT_FOUND} · {@code CHAT_ROOM_ACCESS_DENIED})를 그대로 쓴다.
 */
@Service
public class ChatImageService {

  /** 키의 접두어. 버킷 정책이 공개 읽기를 {@code profile/*} 로 좁히는 근거도 이 구분이다. */
  private static final String KEY_PREFIX = "chat/";

  private final ChatRoomMembershipReader chatRoomReader;
  private final ChatImageRepository chatImageRepository;
  private final ChatImageStorage storage;
  private final ChatImagePolicy policy;
  private final Duration presignTtl;

  public ChatImageService(
      ChatRoomMembershipReader chatRoomReader,
      ChatImageRepository chatImageRepository,
      ChatImageStorage storage,
      @Value("${duckmoim.chat.image.allowed-content-types}") List<String> allowedContentTypes,
      @Value("${duckmoim.chat.image.max-bytes}") long maxBytes,
      @Value("${duckmoim.s3.presign-ttl}") Duration presignTtl) {
    this.chatRoomReader = chatRoomReader;
    this.chatImageRepository = chatImageRepository;
    this.storage = storage;
    this.policy = new ChatImagePolicy(allowedContentTypes, maxBytes);
    this.presignTtl = presignTtl;
  }

  /**
   * 서명된 업로드 주소를 발급한다 (CH-14).
   *
   * <p><b>여기가 검증 기준의 「허용 밖 형식·크기 400」이 나는 자리다.</b> 클라이언트가 선언한 값을 보고, 정책을 벗어나면 서명을 만들지 않는다 — 올리기 전에
   * 막는 것이 올린 뒤 버리는 것보다 싸다.
   *
   * <p><b>선언은 거짓말일 수 있다.</b> 5MB 라 하고 50MB 를 올리면 이 검사는 통과한다. 그래서 {@link #confirm} 이 실제 값을 다시 본다.
   *
   * <p><b>판정을 먼저 하고 행을 만든다.</b> 순서가 뒤집히면 형식이 틀린 요청마다 {@code PENDING} 행이 남아, 배치가 지울 것 없는 행을 계속 집는다.
   *
   * <p><b>키에 UUID 를 넣는다.</b> 방 번호와 회원번호만으로 만들면 같은 주소를 덮어써서 옛 사진이 새 메시지에 붙는다.
   *
   * @throws BusinessException 방이 없으면 404, 멤버가 아니면 403, 정책을 벗어나면 형식·크기별 400
   */
  @Transactional
  public ChatImageUpload issueUpload(
      Long roomId, Long uploaderId, String contentType, long contentLength) {

    chatRoomReader.requireMember(roomId, uploaderId);
    policy.validate(contentType, contentLength);

    String objectKey = objectKeyOf(roomId, contentType);
    ChatImage image =
        chatImageRepository.save(ChatImage.pending(roomId, uploaderId, objectKey, contentType));

    return new ChatImageUpload(
        image.getId(), storage.presignUpload(objectKey, contentType), presignTtl.toSeconds());
  }

  /**
   * 올라간 것을 확인한다 (CH-14).
   *
   * <p><b>셋을 본다.</b> 그 방에 내가 올린 행인지 · 저장소에 실제로 있는지 · 실제 형식과 크기가 정책 안인지.
   *
   * <p><b>앞의 둘이 같은 코드로 답한다.</b> 「남의 것이다」로 답하면 <b>그 번호의 사진이 존재한다는 사실</b>을 알려준다 — AU-08 이 접두어 검사와 존재
   * 검사에 같은 코드를 쓴 것과 같은 판단이다.
   *
   * <p><b>형식·크기만 갈라서 던진다.</b> 사용자가 고칠 수 있는 것이고, 무엇을 고쳐야 하는지가 다르다.
   *
   * <p><b>두 번 불러도 같은 결과다.</b> 확정 응답을 못 받은 클라이언트가 다시 부르는 것이 정상 경로다 ({@code ChatImage#confirm}).
   *
   * @throws BusinessException 없거나 남의 것이거나 저장소에 없으면 {@code CHAT_IMAGE_NOT_UPLOADED}, 실제 값이 정책을 벗어나면
   *     형식·크기별 코드
   */
  @Transactional
  public void confirm(Long roomId, Long uploaderId, Long imageId) {
    chatRoomReader.requireMember(roomId, uploaderId);

    ChatImage image = requireOwned(roomId, uploaderId, imageId);
    UploadedChatImage uploaded =
        storage
            .findUploaded(image.getObjectKey())
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_IMAGE_NOT_UPLOADED));

    policy.validate(uploaded.contentType(), uploaded.contentLength());

    image.confirm(uploaded.contentType(), uploaded.contentLength());
  }

  /**
   * 그 방에 그 사람이 올린 행을 집는다.
   *
   * <p>없는 번호 · 남의 번호 · 다른 방의 번호가 모두 같은 답이다.
   */
  private ChatImage requireOwned(Long roomId, Long uploaderId, Long imageId) {
    return Optional.ofNullable(imageId)
        .flatMap(chatImageRepository::findById)
        .filter(found -> found.isUploadedBy(roomId, uploaderId))
        .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_IMAGE_NOT_UPLOADED));
  }

  /** 키를 만드는 규칙 한 곳. 접두어가 버킷 정책의 경계와 같은 값이라 바꿀 때 정책도 함께 본다. */
  private String objectKeyOf(Long roomId, String contentType) {
    return KEY_PREFIX + roomId + "/" + UUID.randomUUID() + "." + policy.extensionOf(contentType);
  }
}
