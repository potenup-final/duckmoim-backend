package com.duckmoim.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationMute;
import com.duckmoim.common.infra.NotificationMuteRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종류별 수신 설정 (NT-11).
 *
 * <p><b>검증 기준이 한 줄이다</b> — 채팅 알림만 끄면 댓글 알림은 계속 온다.
 *
 * <p><b>끊는 자리가 발행 직전이라 여기서 본다.</b> 만들어 두고 목록에서 거르는 방식이 아니어서, 「안 쌓인다」를 아웃박스에서 확인하는 것이 곧 검증이다 — 알림함이나
 * 배지까지 내려가 보지 않아도 그 아래는 아웃박스가 비었다는 사실에서 따라온다.
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙인다.</b> {@code NotificationOutboxPublisher} 가 {@code
 * MANDATORY} 라 트랜잭션 없이는 부를 수 없고, 여기서 보는 것은 「행이 생겼나」 하나라 커밋될 필요가 없다. {@code
 * NotificationDispatchServiceTest} 가 트랜잭션을 안 쓰는 것과 갈리는 지점이다 — 그쪽은 발송과 실패 기록의 <b>경계</b>를 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("종류별 수신 설정")
class NotificationMuteTest {

  private static final long ME = 770001L;
  private static final long ACTOR = 770002L;
  private static final long ROOM_MATE = 770003L;
  private static final long POST_ID = 10L;
  private static final long COMMENT_ID = 100L;
  private static final long ROOM_ID = 3L;
  private static final long MESSAGE_ID = 777L;

  @Autowired private NotificationOutboxPublisher notificationOutboxPublisher;
  @Autowired private NotificationMuteRepository notificationMuteRepository;
  @Autowired private JdbcTemplate jdbc;
  @PersistenceContext private EntityManager entityManager;

  /** <b>이 검사가 NT-11 의 검증 기준이다.</b> 전체 끄기 하나로 대신하면 여기서 걸린다. */
  @DisplayName("채팅 알림만 끄면 댓글 알림은 계속 온다.")
  @Test
  void publish_keepsOtherKindsWhenOneIsMuted() {
    // given
    mute(ME, NotificationKind.ROOM_MESSAGED);

    // when
    publishAllThree();

    // then
    assertThat(kindsFor(ME))
        .containsExactlyInAnyOrder(
            NotificationKind.POST_COMMENTED, NotificationKind.COMMENT_REPLIED);
  }

  @DisplayName("설정을 한 번도 만지지 않았으면 셋 다 쌓인다.")
  @Test
  void publish_deliversEveryKindByDefault() {
    // when — 행이 없는 것이 곧 「전부 받는다」다 (V807)
    publishAllThree();

    // then
    assertThat(kindsFor(ME)).hasSize(3);
  }

  @DisplayName("끈 종류는 아웃박스에 쌓이지 않는다.")
  @Test
  void publish_skipsMutedKind() {
    // given
    mute(ME, NotificationKind.POST_COMMENTED);

    // when
    notificationOutboxPublisher.postCommented(ME, ACTOR, POST_ID, COMMENT_ID);

    // then — 만들어 두고 거르는 것이 아니라 행 자체가 없다
    assertThat(kindsFor(ME)).isEmpty();
  }

  /** 억제가 「지금 설정」에만 걸린다. 한 번 끄면 영영 안 오는 것은 수신 설정이 아니라 탈퇴다. */
  @DisplayName("껐다 켜면 다시 쌓인다.")
  @Test
  void publish_resumesAfterUnmute() {
    // given
    NotificationMute muted = mute(ME, NotificationKind.POST_COMMENTED);
    notificationMuteRepository.delete(muted);
    notificationMuteRepository.flush();

    // when
    notificationOutboxPublisher.postCommented(ME, ACTOR, POST_ID, COMMENT_ID);

    // then
    assertThat(kindsFor(ME)).containsExactly(NotificationKind.POST_COMMENTED);
  }

  /** 수신자가 여럿인 경로다. 목록째로 한 번에 걸러도 껐다는 사실은 사람마다 따로다. */
  @DisplayName("방의 한 명만 껐으면 나머지에게는 쌓인다.")
  @Test
  void roomMessaged_skipsOnlyMutedMembers() {
    // given
    mute(ME, NotificationKind.ROOM_MESSAGED);

    // when
    notificationOutboxPublisher.roomMessaged(List.of(ME, ROOM_MATE), ACTOR, ROOM_ID, MESSAGE_ID);

    // then
    assertThat(kindsFor(ME)).isEmpty();
    assertThat(kindsFor(ROOM_MATE)).containsExactly(NotificationKind.ROOM_MESSAGED);
  }

  /**
   * 토글은 몇 번을 눌러도 결과가 같아야 한다.
   *
   * <p>두 줄이 생기면 켤 때 하나만 지워져 <b>꺼진 채로 남는다</b> — 사용자가 켰다고 믿는 상태와 서버가 아는 상태가 갈린다.
   */
  @DisplayName("같은 종류를 두 번 꺼도 한 건이다.")
  @Test
  void mute_isUniquePerKind() {
    // given
    mute(ME, NotificationKind.ROOM_MESSAGED);

    // when & then — uq_notification_mute (V807)
    assertThatThrownBy(() -> mute(ME, NotificationKind.ROOM_MESSAGED))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void publishAllThree() {
    notificationOutboxPublisher.postCommented(ME, ACTOR, POST_ID, COMMENT_ID);
    notificationOutboxPublisher.commentReplied(ME, ACTOR, POST_ID, COMMENT_ID);
    notificationOutboxPublisher.roomMessaged(List.of(ME), ACTOR, ROOM_ID, MESSAGE_ID);
  }

  private NotificationMute mute(long userId, NotificationKind kind) {
    return notificationMuteRepository.saveAndFlush(NotificationMute.of(userId, kind));
  }

  /**
   * 이 검사가 만든 것만 센다. 회원번호를 크게 잡아 남의 픽스처와 겹치지 않는다.
   *
   * <p><b>먼저 {@code flush} 한다.</b> 발행은 영속성 컨텍스트에만 들어가 있고 원시 SQL 은 그것을 못 본다 — 안 흘려보내면 쌓인 것도 빈 목록으로
   * 읽힌다.
   *
   * <p><b>저장소를 쓰지 않는 것은 {@code NotificationOutboxRepository} 가 {@code Repository} 를 좁게 상속했기
   * 때문이다.</b> 워커가 쓰는 메서드만 열려 있고 {@code findAll} 이 없다. 검사 하나 때문에 그 문을 넓히지 않는다.
   */
  private List<NotificationKind> kindsFor(long recipientId) {
    entityManager.flush();

    return jdbc
        .queryForList("SELECT kind FROM notification_outbox WHERE recipient_id = ?", recipientId)
        .stream()
        .map(row -> NotificationKind.valueOf((String) row.get("kind")))
        .toList();
  }
}
