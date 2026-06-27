package com.myplus.business_service;

import com.myplus.common.web.CommonWebAutoConfiguration;
import org.modelmapper.ModelMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

// Reuse common-web's exception CLASSES (dedup, slice 39) but NOT its shared GlobalExceptionHandler: business-service
// is monolith-facing and keeps its own handler (Bean-Validation → HTTP 200 + GenericResponse). Excluding the
// auto-config prevents a second @RestControllerAdvice from registering and overriding that behaviour.
@SpringBootApplication(exclude = CommonWebAutoConfiguration.class)
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.myplus.business_service.repository")

public class BusinessServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BusinessServiceApplication.class, args);
    }

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
