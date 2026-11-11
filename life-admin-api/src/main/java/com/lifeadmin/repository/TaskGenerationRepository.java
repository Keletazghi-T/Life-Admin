package com.lifeadmin.repository;
import com.lifeadmin.entity.TaskGeneration;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface TaskGenerationRepository extends JpaRepository<TaskGeneration, UUID> {}
