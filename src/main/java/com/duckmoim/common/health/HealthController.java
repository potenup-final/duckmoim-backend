package com.duckmoim.common.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 얕은 생존 신호다. <b>일부러 아무것도 검사하지 않는다.</b>
 *
 * <p>깊은 판정은 {@code /actuator/health} 가 한다 — DB 커넥션을 실제로 얻어 보고 실패하면 503 을 낸다. ALB 대상 그룹의 상태 검사와
 * {@code ci-cd.yml} 의 배포 전환 게이트가 그쪽을 본다.
 *
 * <p>둘로 나눈 이유는 <b>「프로세스가 떠 있는가」와 「요청을 처리할 수 있는가」가 다른 질문</b>이어서다. 배포 직후처럼 아직 DB 에 못 붙은 상태를 「죽었으니
 * 컨테이너를 재시작하라」로 읽으면 안 된다.
 *
 * <p>이 경로를 지우지 않는다. 위키 {@code API-설계.md} 「엔드포인트 목록」이 {@code PUBLIC} 계약으로 못박았고, 전환 뒤 스모크 테스트가 ALB 를
 * 통과하는 유일한 호출이라 {@code /actuator/*} 를 인터넷에서 막아도 살아 있어야 한다.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

  @GetMapping
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }
}
