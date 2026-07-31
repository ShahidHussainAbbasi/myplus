package com.myplus.education;

import com.myplus.common.web.CommonWebAutoConfiguration;
import org.modelmapper.ModelMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.Bean;

// Exclude CommonWebAutoConfiguration: education owns its own @RestControllerAdvice GlobalExceptionHandler
// (monolith-facing, returns GenericResponse). common-web is on the classpath only for the ApiResponse class
// the shared common-settings SettingsController uses — not its exception advice. Same pattern as business.
@SpringBootApplication(exclude = CommonWebAutoConfiguration.class)
// Slice 0.1: required for GlOutboxService.flushPending() — without it the @Scheduled relay never runs, so a GL
// event that failed its first delivery would stay PENDING forever and the books would silently drift.
@org.springframework.scheduling.annotation.EnableScheduling
public class EducationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EducationServiceApplication.class, args);
    }

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
