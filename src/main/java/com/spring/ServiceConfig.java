package com.spring;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
//@ComponentScan({ "com.service" })
@ComponentScan(basePackages = { "com.service*","com.web*" })
public class ServiceConfig {
}
