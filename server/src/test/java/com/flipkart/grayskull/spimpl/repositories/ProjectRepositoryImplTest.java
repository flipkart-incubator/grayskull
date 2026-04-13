package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.ProjectEntity;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spimpl.repositories.mongo.ProjectMongoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ProjectRepositoryImpl Unit Tests")
class ProjectRepositoryImplTest {

    ProjectMongoRepository mongoRepository = mock();
    ProjectRepositoryImpl repository = new ProjectRepositoryImpl(mongoRepository);

    @Test
    @DisplayName("findAllById should delegate to mongoRepository and return projects")
    void findAllById_shouldDelegateAndReturnProjects() {
        ProjectEntity entity = ProjectEntity.builder().id("proj-a").kmsKeyId("k1").build();
        when(mongoRepository.findAllById(any())).thenReturn(List.of(entity));

        List<Project> result = repository.findAllById(Set.of("proj-a"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("proj-a");
        verify(mongoRepository).findAllById(any());
    }

    @Test
    @DisplayName("findAllById should return empty list when no projects found")
    void findAllById_shouldReturnEmpty_whenNoProjectsFound() {
        when(mongoRepository.findAllById(any())).thenReturn(List.of());

        List<Project> result = repository.findAllById(Set.of("missing"));

        assertThat(result).isEmpty();
    }
}
