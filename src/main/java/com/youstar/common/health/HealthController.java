package com.youstar.common.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인스턴스가 요청을 받을 수 있는 상태인지 확인하는 지점.
 *
 * 배포 직후 컨테이너가 실제로 떴는지 확인하는 용도이므로 DB 같은 외부 의존을
 * 일부러 건드리지 않는다. 의존이 늘어난 뒤에도 이 응답은 "이 프로세스가 살아
 * 있다"는 뜻만 유지해야, 배포가 실패했을 때 원인을 좁힐 수 있다.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

	@GetMapping
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}
}
