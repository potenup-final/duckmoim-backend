package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.infra.CompanionPostRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만남시각이 지난 모집글을 닫는다 (PO-14).
 *
 * <p><b>청크 하나가 트랜잭션 하나다.</b> 밀린 글 전부를 한 트랜잭션에 담으면 롤백 단위가 통째가 되고, 잠긴 행이 커밋 전까지 풀리지 않아 방장이 그 글을 마감하려는
 * 요청이 기다린다. 배포 직후 첫 실행은 그동안 밀린 글을 전부 만나므로 그 건수가 얼마인지 알 수 없다.
 *
 * <p><b>주기와 반복은 여기 없다.</b> {@code MeetTimePassedCloseBatch} 가 진다 — 이 메서드가 트랜잭션 경계이고 같은 클래스 안에서 자기를
 * 부르면 프록시를 지나지 않아 {@code @Transactional} 이 걸리지 않는다. 빈 둘로 나누는 이유가 그것이다.
 *
 * <p><b>현재 시각을 받는다.</b> {@code Clock} 을 여기서 읽지 않는 것은 「지난 글」과 「안 지난 글」의 경계가 검증 대상이라서다 — 시각을 인자로 두면
 * 테스트가 실행 시각에 결과를 맡기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MeetTimePassedCloseService {

  private final CompanionPostRepository companionPostRepository;

  /**
   * 청크 하나만큼 닫는다.
   *
   * <p><b>{@code save} 를 부르지 않는다.</b> 이미 영속 상태인 엔티티라 트랜잭션이 끝날 때 더티 체킹이 반영한다 — {@code
   * CompanionPostCommandService.edit} 이 같은 판단을 했다.
   *
   * <p><b>돌려주는 건수가 곧 집은 건수다.</b> 조회가 {@code FOR UPDATE} 로 행을 잠그고 오므로, 그 사이에 방장이 같은 글을 마감해 도메인이
   * {@code false} 를 돌려주는 경우가 없다 — 잠기기 전에 마감됐다면 애초에 {@code OPEN} 조건에 걸리지 않는다. 그래서 부르는 쪽이 이 값을 「청크가 꽉
   * 찼는가」로 읽어도 된다.
   *
   * @param nowInUtc UTC 기준 현재 시각. KST 벽시계를 넣으면 아직 만나지 않은 아홉 시간 안쪽의 글이 닫힌다
   * @param chunk 한 번에 닫을 최대 건수
   * @return 이 호출이 닫은 건수
   */
  @Transactional
  public int closeChunk(LocalDateTime nowInUtc, int chunk) {
    List<CompanionPost> posts =
        companionPostRepository.findOpenPostsWithMeetTimePassed(
            nowInUtc, PageRequest.ofSize(chunk));

    int closed = 0;
    for (CompanionPost post : posts) {
      if (post.closeForMeetTimePassed(nowInUtc)) {
        closed++;
      }
    }

    return closed;
  }
}
