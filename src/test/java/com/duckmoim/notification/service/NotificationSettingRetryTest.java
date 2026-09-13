package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.infra.NotificationMuteRepository;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 설정 저장이 충돌했을 때 다시 시도한다 (NT-11 · PR #148 리뷰).
 *
 * <p>토글을 연달아 누르면 같은 사람의 {@code PUT} 이 겹치는데, 「지우고 다시 넣기」가 MySQL 의 전형적인 데드락 모양이라 한쪽이 500 으로 죽을 수 있다.
 *
 * <p><b>진짜 데드락을 만들지 않는다.</b> 두 트랜잭션의 락 순서를 시험에서 맞추려면 스레드와 커넥션을 손으로 엮어야 하는데, 그렇게 만든 검사는 <b>DB 의
 * 스케줄링에 기대서 가끔 초록불이 된다.</b> 여기서 보는 것은 「충돌이 올라왔을 때 이 클래스가 무엇을 하는가」이고 그것은 대역으로 정확히 만들 수 있다.
 *
 * <p><b>스프링을 띄우지 않는다.</b> {@code @SpringBootTest} 를 하나 더 만들면 남의 테스트가 커넥션을 못 얻는다 (CLAUDE.md 「겪은
 * 함정」).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("설정 저장 충돌")
class NotificationSettingRetryTest {

  private static final long USER_ID = 770001L;
  private static final Set<NotificationKind> MUTED = EnumSet.of(NotificationKind.ROOM_MESSAGED);

  @Mock private NotificationMuteRepository notificationMuteRepository;
  @Mock private NotificationMuteWriter notificationMuteWriter;

  @InjectMocks private NotificationSettingService notificationSettingService;

  /** 진 트랜잭션은 롤백된 뒤라, 다음 시도는 이긴 쪽이 커밋을 마친 상태에서 시작한다. */
  @DisplayName("데드락으로 한 번 지면 다시 시도해서 저장한다.")
  @Test
  void replaceMutedKinds_retriesAfterDeadlock() {
    willThrow(new CannotAcquireLockException("데드락"))
        .willDoNothing()
        .given(notificationMuteWriter)
        .replace(anyLong(), any());

    assertThatCode(() -> notificationSettingService.replaceMutedKinds(USER_ID, MUTED))
        .doesNotThrowAnyException();

    then(notificationMuteWriter).should(times(2)).replace(USER_ID, MUTED);
  }

  /** 남이 먼저 커밋하면 내 DELETE 가 못 본 행을 내가 다시 넣게 된다. 다시 시도하면 그때는 그 행이 보인다. */
  @DisplayName("중복 키로 지면 다시 시도해서 저장한다.")
  @Test
  void replaceMutedKinds_retriesAfterDuplicateKey() {
    willThrow(new DataIntegrityViolationException("uq_notification_mute"))
        .willDoNothing()
        .given(notificationMuteWriter)
        .replace(anyLong(), any());

    assertThatCode(() -> notificationSettingService.replaceMutedKinds(USER_ID, MUTED))
        .doesNotThrowAnyException();

    then(notificationMuteWriter).should(times(2)).replace(USER_ID, MUTED);
  }

  /**
   * <b>무한히 돌지 않는다.</b> 계속 지는 것은 재시도로 풀 문제가 아니라 알아야 할 고장이라 그대로 올린다 — 삼키면 사용자는 200 을 받는데 설정은 안 바뀐다.
   */
  @DisplayName("계속 충돌하면 세 번째에 그대로 올린다.")
  @Test
  void replaceMutedKinds_givesUpAfterMaxAttempts() {
    willThrow(new CannotAcquireLockException("데드락"))
        .given(notificationMuteWriter)
        .replace(anyLong(), any());

    assertThatThrownBy(() -> notificationSettingService.replaceMutedKinds(USER_ID, MUTED))
        .isInstanceOf(CannotAcquireLockException.class);

    then(notificationMuteWriter).should(times(3)).replace(USER_ID, MUTED);
  }

  /** 평상시에는 한 번만 부른다. 재시도가 조용히 늘 도는 상태가 되면 그것부터 잘못이다. */
  @DisplayName("충돌이 없으면 한 번만 저장한다.")
  @Test
  void replaceMutedKinds_writesOnceWhenNothingConflicts() {
    willDoNothing().given(notificationMuteWriter).replace(anyLong(), any());

    notificationSettingService.replaceMutedKinds(USER_ID, MUTED);

    then(notificationMuteWriter).should(times(1)).replace(USER_ID, MUTED);
  }
}
