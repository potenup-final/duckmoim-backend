package com.duckmoim.common.infra;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationMute;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 끈 알림 종류를 읽고 쓴다 (NT-11).
 *
 * <p><b>읽는 자리가 둘이다.</b> 아웃박스 발행 직전과 설정 화면이고, {@code uq_notification_mute (user_id, kind)} 하나가 둘 다
 * 받는다 (V807).
 */
public interface NotificationMuteRepository extends JpaRepository<NotificationMute, Long> {

  /** 이 사람이 이 종류를 껐는가. <b>댓글 알림의 발행 직전</b>이 부르는 자리다 (수신자가 한 명이다). */
  boolean existsByUserIdAndKind(Long userId, NotificationKind kind);

  /**
   * 준 사람들 중 이 종류를 끈 사람. <b>채팅 알림의 발행 직전</b>이 부르는 자리다 (수신자가 여럿이다).
   *
   * <p><b>한 번에 묻는 것이 요점이다.</b> 한 사람씩 물으면 6명 방의 메시지 한 건이 조회 다섯이 되고, 그 자리는 메시지 전송 트랜잭션 안이다.
   *
   * <p>엔티티가 아니라 회원번호만 돌려준다 — 부르는 쪽이 하는 일이 「이 목록에서 빼기」 하나다.
   */
  @Query("select m.userId from NotificationMute m where m.kind = :kind and m.userId in :userIds")
  List<Long> findMutedUserIds(
      @Param("kind") NotificationKind kind, @Param("userIds") Collection<Long> userIds);

  /** 이 사람이 끈 종류 전부. <b>설정 화면</b>이 부르는 자리다. */
  @Query("select m.kind from NotificationMute m where m.userId = :userId")
  List<NotificationKind> findMutedKinds(@Param("userId") Long userId);

  /**
   * 이 사람의 설정을 통째로 지운다. 저장이 「지우고 다시 넣기」라 그 앞단이다.
   *
   * <p><b>지운 뒤에 다시 넣는 이유는 PUT 이기 때문이다.</b> 무엇이 늘고 줄었는지 따져 부분만 고치면 계산이 하나 더 생기는데, 한 사람의 행이 많아야 종류
   * 수(셋)다.
   */
  void deleteByUserId(Long userId);
}
