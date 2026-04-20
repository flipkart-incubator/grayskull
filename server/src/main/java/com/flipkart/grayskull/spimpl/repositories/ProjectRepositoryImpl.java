package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.ProjectEntity;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.ProjectMongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data MongoDB repository implementation for Project.
 * Implements the SPI contract using Spring Data.
 */
@Repository
public class ProjectRepositoryImpl implements ProjectRepository {

    private final ProjectMongoRepository mongoRepository;

    public ProjectRepositoryImpl(ProjectMongoRepository mongoRepository) {
        this.mongoRepository = mongoRepository;
    }

    @Override
    public Optional<Project> findById(String id) {
        return mongoRepository.findById(id).map(entity -> entity);
    }

    @Override
    public Project findByIdOrTransient(String id) {
        return mongoRepository.findById(id)
                .map(Project.class::cast)
                .orElseGet(() -> ProjectEntity.builder().id(id).kmsKeyId(null).build());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends Project> S save(S entity) {
        if (!(entity instanceof ProjectEntity)) {
            throw new IllegalArgumentException(
                    "Expected ProjectEntity but got: " + entity.getClass().getName());
        }
        return (S) mongoRepository.save((ProjectEntity) entity);
    }
}
