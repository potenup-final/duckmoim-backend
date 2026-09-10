package com.duckmoim.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.companion.domain.CompanionPostOpened;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 방 개설이 모집글 작성의 트랜잭션 안에서만 돈다 (CH-01).
 *
 * <p><b>이 티켓의 설계 결정을 검증한다.</b> 도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」이 <i>"한 트랜잭션에서 두 애그리게이트를 함께 수정하지
 * 않는다"</i> 고 정했고 여기는 그 예외다. 근거는 CH-01a 가 마이그레이션을 두는 이유 — <i>"조회 경로에 「방이 없는 경우」 분기를 두지 않기 위해서"</i> —
 * 인데, 방 개설이 별도 트랜잭션이면 그 분기가 방금 만든 모집글에서 다시 생긴다.
 *
 * <p><b>{@code @Transactional} 을 클래스에 걸지 않았다.</b> 여기서 보려는 것이 트랜잭션이 없을 때의 거절이라, 테스트가 트랜잭션을 열면 검증 대상이
 * 사라진다. 그래서 {@code ChatRoomOpenServiceTest} 와 나눠 두었다.
 *
 * <p>거절이 곧 원자성의 증명이다 — 이 경로가 남의 트랜잭션에 올라타는 것 말고는 돌 방법이 없으므로, 모집글 작성이 되돌려지면 방도 함께 되돌려진다.
 */
@SpringBootTest
class ChatRoomOpenTransactionTest {

  private static final long POST_ID = 900_002L;
  private static final long HOST_ID = 1L;

  @Autowired private ChatRoomOpenService chatRoomOpenService;
  @Autowired private ChatRoomRepository chatRoomRepository;

  @DisplayName("트랜잭션 없이 방을 열려고 하면 거절한다.")
  @Test
  void openFor_noTransaction() {
    CompanionPostOpened opened = new CompanionPostOpened(POST_ID, HOST_ID);

    assertThatThrownBy(() -> chatRoomOpenService.openFor(opened))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(chatRoomRepository.findByPostId(POST_ID)).isEmpty();
  }
}
