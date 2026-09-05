package com.duckmoim.admin.infra;

import com.duckmoim.admin.domain.AdminAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

  boolean existsByKakaoUserId(Long kakaoUserId);
}
