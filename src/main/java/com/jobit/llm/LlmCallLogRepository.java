package com.jobit.llm;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, UUID> {
}
