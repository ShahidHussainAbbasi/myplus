package com.myplus.agriculture;

import com.myplus.common.web.CommonWebAutoConfiguration;
import org.modelmapper.ModelMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.Bean;

// Exclude CommonWebAutoConfiguration: agriculture owns its own GlobalExceptionHandler (same default bean name as
// common-web's advice → boot-time name collision). common-web is on the classpath only for the ApiResponse the
// shared common-settings SettingsController uses. Same pattern as business/education/welfare.
@SpringBootApplication(exclude = CommonWebAutoConfiguration.class)
public class AgricultureServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgricultureServiceApplication.class, args);
    }

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
