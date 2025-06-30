package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/v1/admin")
@AllArgsConstructor
@Validated
public class AdminController {

    private final SecretService secretService;

    @GetMapping("/project/{projectId}/secrets/{secretName}/data/{version}")
    public SecretDataVersionResponse getSecretDataVersion(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName, @PathVariable("version") @Min(1) int version) {
        return secretService.getSecretDataVersion(projectId, secretName, version);
    }
} 