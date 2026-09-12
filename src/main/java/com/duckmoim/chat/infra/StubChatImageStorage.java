package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.domain.UploadedChatImage;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 버킷이 설정되지 않았을 때 뜨는 대역 (CH-14).
 *
 * <p><b>프로파일로 가르지 않는다.</b> {@code @Profile("prod")} 로 묶으면 자격증명을 가진 개발자가 로컬에서 실물을 확인할 방법이 없어진다 —
 * {@code StubProfileImageStorage} 가 같은 판단을 적어 두었다. 버킷 이름을 채우면 S3 구현이 뜨고 이 대역은 물러난다.
 *
 * <p><b>확인 요청에 빈 값을 답한다.</b> 「있다」고 답하면 확정이 통과해 <b>없는 객체를 가리키는 행이 CONFIRMED 로 남고</b>, 그 행이 메시지에 실린다
 * — 대역이 만들 수 있는 가장 나쁜 상태다. 그래서 로컬에서 이미지 전송을 끝까지 해보려면 버킷이 있어야 한다.
 *
 * <p><b>삭제는 성공으로 답한다.</b> 지울 객체가 애초에 없고, 실패로 답하면 고아 정리 배치가 매 주기 같은 행을 다시 집어 로그만 쌓인다.
 */
@Slf4j
@Configuration
public class StubChatImageStorage {

  /** 메서드 이름을 클래스 이름과 다르게 둔다 — 같으면 이 설정 클래스 자신의 빈 이름과 부딪혀 기동이 실패한다 (PR #89 리뷰). */
  @Bean
  @ConditionalOnMissingBean(ChatImageStorage.class)
  public ChatImageStorage disabledChatImageStorage() {
    log.warn("[StubChatImageStorage.disabledChatImageStorage] 버킷이 설정되지 않아 채팅 이미지 업로드를 끈다.");

    return new ChatImageStorage() {

      @Override
      public String presignUpload(String objectKey, String contentType) {
        return "https://chat-image-storage-is-not-configured.invalid/" + objectKey;
      }

      @Override
      public Optional<UploadedChatImage> findUploaded(String objectKey) {
        return Optional.empty();
      }

      @Override
      public boolean delete(String objectKey) {
        return true;
      }
    };
  }
}
