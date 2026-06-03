package com.web.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;


import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;

@Component
public class EducationRestClient {

    @Value("${education.service.url:http://localhost:8084}")
    private String baseUrl;

    private final RestTemplate restTemplate;

    public EducationRestClient(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    public ResponseEntity<String> get(String path, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Auth-UserId", userId != null ? String.valueOf(userId) : "");
        return restTemplate.exchange(
            baseUrl + path, HttpMethod.GET,
            new HttpEntity<>(headers), String.class
        );
    }

    public ResponseEntity<String> post(String path, HttpServletRequest request, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Auth-UserId", userId != null ? String.valueOf(userId) : "");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            for (String value : request.getParameterValues(name)) {
                params.add(name, value);
            }
        }
        return restTemplate.exchange(
            baseUrl + path, HttpMethod.POST,
            new HttpEntity<>(params, headers), String.class
        );
    }

    public ResponseEntity<String> postMultipart(String path, MultipartFile file, HttpServletRequest request, Long userId) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Auth-UserId", userId != null ? String.valueOf(userId) : "");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return file.getOriginalFilename(); }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        return restTemplate.exchange(
            baseUrl + path, HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class
        );
    }
}
