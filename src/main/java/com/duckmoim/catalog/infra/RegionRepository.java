package com.duckmoim.catalog.infra;

import com.duckmoim.catalog.domain.Region;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, Long> {}
