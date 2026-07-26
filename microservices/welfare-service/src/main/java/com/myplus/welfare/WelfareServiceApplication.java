package com.myplus.welfare;

import com.myplus.common.web.CommonWebAutoConfiguration;
import org.modelmapper.ModelMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.Bean;

// Exclude CommonWebAutoConfiguration: welfare owns its own GlobalExceptionHandler (same default bean name as
// common-web's advice → boot-time name collision). common-web is on the classpath only for the ApiResponse the
// shared common-settings SettingsController uses. Same pattern as business/education.
@SpringBootApplication(exclude = CommonWebAutoConfiguration.class)
public class WelfareServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WelfareServiceApplication.class, args);
    }

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
