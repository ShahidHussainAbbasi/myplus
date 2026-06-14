#!/bin/bash
ROOT="C:/Users/HP/Shahid/software/myplus/microservices"

gen_java() {
  local svc=$1
  local cap=$2
  local base="$ROOT/${svc}-service"
  local pkg="$base/src/main/java/com/myplus/${svc}"

  # Application.java
  {
    printf 'package com.myplus.%s;\n\n' "$svc"
    printf 'import org.modelmapper.ModelMapper;\n'
    printf 'import org.springframework.boot.SpringApplication;\n'
    printf 'import org.springframework.boot.autoconfigure.SpringBootApplication;\n'
    printf 'import org.springframework.cloud.netflix.eureka.EnableEurekaClient;\n'
    printf 'import org.springframework.context.annotation.Bean;\n\n'
    printf '@SpringBootApplication\n'
    printf '@EnableEurekaClient\n'
    printf 'public class %sServiceApplication {\n' "$cap"
    printf '    public static void main(String[] args) {\n'
    printf '        SpringApplication.run(%sServiceApplication.class, args);\n' "$cap"
    printf '    }\n\n'
    printf '    @Bean\n'
    printf '    public ModelMapper modelMapper() {\n'
    printf '        return new ModelMapper();\n'
    printf '    }\n'
    printf '}\n'
  } > "$pkg/${cap}ServiceApplication.java"

  # ApiResponse
  {
    printf 'package com.myplus.%s.dto;\n\n' "$svc"
    printf 'import lombok.AllArgsConstructor;\nimport lombok.Data;\nimport lombok.NoArgsConstructor;\n\n'
    printf '@Data @AllArgsConstructor @NoArgsConstructor\n'
    printf 'public class ApiResponse<T> {\n'
    printf '    private boolean success;\n'
    printf '    private String message;\n'
    printf '    private T data;\n'
    printf '    private int statusCode;\n\n'
    printf '    public static <T> ApiResponse<T> success(T data) { return new ApiResponse<>(true, "Success", data, 200); }\n'
    printf '    public static <T> ApiResponse<T> success(T data, String message) { return new ApiResponse<>(true, message, data, 200); }\n'
    printf '    public static <T> ApiResponse<T> error(String message, int code) { return new ApiResponse<>(false, message, null, code); }\n'
    printf '}\n'
  } > "$pkg/dto/ApiResponse.java"

  # PageResponse
  {
    printf 'package com.myplus.%s.dto;\n\n' "$svc"
    printf 'import lombok.AllArgsConstructor;\nimport lombok.Data;\nimport lombok.NoArgsConstructor;\nimport org.springframework.data.domain.Page;\n\n'
    printf 'import java.util.List;\nimport java.util.function.Function;\n\n'
    printf '@Data @AllArgsConstructor @NoArgsConstructor\n'
    printf 'public class PageResponse<T> {\n'
    printf '    private List<T> content;\n'
    printf '    private int pageNo;\n'
    printf '    private int pageSize;\n'
    printf '    private long totalElements;\n'
    printf '    private int totalPages;\n'
    printf '    private boolean last;\n\n'
    printf '    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {\n'
    printf '        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),\n'
    printf '                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), page.isLast());\n'
    printf '    }\n'
    printf '}\n'
  } > "$pkg/dto/PageResponse.java"

  # Exceptions
  printf 'package com.myplus.%s.exception;\npublic class ResourceNotFoundException extends RuntimeException { public ResourceNotFoundException(String m) { super(m); } }\n' "$svc" > "$pkg/exception/ResourceNotFoundException.java"
  printf 'package com.myplus.%s.exception;\npublic class DuplicateResourceException extends RuntimeException { public DuplicateResourceException(String m) { super(m); } }\n' "$svc" > "$pkg/exception/DuplicateResourceException.java"
  printf 'package com.myplus.%s.exception;\npublic class ValidationException extends RuntimeException { public ValidationException(String m) { super(m); } }\n' "$svc" > "$pkg/exception/ValidationException.java"

  # GlobalExceptionHandler
  {
    printf 'package com.myplus.%s.exception;\n\n' "$svc"
    printf 'import com.myplus.%s.dto.ApiResponse;\n' "$svc"
    printf 'import org.springframework.http.HttpStatus;\n'
    printf 'import org.springframework.http.ResponseEntity;\n'
    printf 'import org.springframework.security.access.AccessDeniedException;\n'
    printf 'import org.springframework.web.bind.MethodArgumentNotValidException;\n'
    printf 'import org.springframework.web.bind.annotation.ExceptionHandler;\n'
    printf 'import org.springframework.web.bind.annotation.RestControllerAdvice;\n'
    printf 'import java.util.stream.Collectors;\n\n'
    printf '@RestControllerAdvice\npublic class GlobalExceptionHandler {\n\n'
    printf '    @ExceptionHandler(ResourceNotFoundException.class)\n'
    printf '    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {\n'
    printf '        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage(), 404));\n    }\n\n'
    printf '    @ExceptionHandler(DuplicateResourceException.class)\n'
    printf '    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {\n'
    printf '        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage(), 409));\n    }\n\n'
    printf '    @ExceptionHandler(ValidationException.class)\n'
    printf '    public ResponseEntity<ApiResponse<Void>> handleValidation(ValidationException ex) {\n'
    printf '        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(ex.getMessage(), 400));\n    }\n\n'
    printf '    @ExceptionHandler(AccessDeniedException.class)\n'
    printf '    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {\n'
    printf '        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Access denied", 403));\n    }\n\n'
    printf '    @ExceptionHandler(MethodArgumentNotValidException.class)\n'
    printf '    public ResponseEntity<ApiResponse<Void>> handleValidationErrors(MethodArgumentNotValidException ex) {\n'
    printf '        String message = ex.getBindingResult().getFieldErrors().stream()\n'
    printf '                .map(err -> err.getField() + ": " + err.getDefaultMessage())\n'
    printf '                .collect(Collectors.joining("; "));\n'
    printf '        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(message, 400));\n    }\n\n'
    printf '    @ExceptionHandler(Exception.class)\n'
    printf '    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {\n'
    printf '        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)\n'
    printf '                .body(ApiResponse.error("Internal server error: " + ex.getMessage(), 500));\n    }\n'
    printf '}\n'
  } > "$pkg/exception/GlobalExceptionHandler.java"

  # AuthenticatedUser
  {
    printf 'package com.myplus.%s.security;\n\n' "$svc"
    printf 'import lombok.AllArgsConstructor;\nimport lombok.Data;\n'
    printf 'import org.springframework.security.core.authority.SimpleGrantedAuthority;\nimport java.util.List;\n\n'
    printf '@Data @AllArgsConstructor\npublic class AuthenticatedUser {\n'
    printf '    private Long userId;\n'
    printf '    private String email;\n'
    printf '    private List<SimpleGrantedAuthority> authorities;\n'
    printf '}\n'
  } > "$pkg/security/AuthenticatedUser.java"

  # SecurityConfig
  {
    printf 'package com.myplus.%s.security;\n\n' "$svc"
    printf 'import lombok.RequiredArgsConstructor;\n'
    printf 'import org.springframework.context.annotation.Bean;\n'
    printf 'import org.springframework.context.annotation.Configuration;\n'
    printf 'import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;\n'
    printf 'import org.springframework.security.config.annotation.web.builders.HttpSecurity;\n'
    printf 'import org.springframework.security.config.http.SessionCreationPolicy;\n'
    printf 'import org.springframework.security.web.SecurityFilterChain;\n'
    printf 'import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;\n\n'
    printf '@Configuration\n@EnableGlobalMethodSecurity(prePostEnabled = true)\n@RequiredArgsConstructor\n'
    printf 'public class SecurityConfig {\n\n'
    printf '    private final HeaderAuthFilter headerAuthFilter;\n\n'
    printf '    @Bean\n'
    printf '    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {\n'
    printf '        http.csrf(csrf -> csrf.disable())\n'
    printf '            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))\n'
    printf '            .authorizeHttpRequests(auth -> auth\n'
    printf '                .antMatchers("/actuator/**", "/api/%s/public/**").permitAll()\n' "$svc"
    printf '                .anyRequest().authenticated())\n'
    printf '            .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);\n'
    printf '        return http.build();\n'
    printf '    }\n'
    printf '}\n'
  } > "$pkg/security/SecurityConfig.java"
}

gen_java inventory Inventory
gen_java business Business
gen_java education Education
gen_java welfare Welfare
gen_java agriculture Agriculture
gen_java pharma Pharma
gen_java marketplace Marketplace
gen_java campaign Campaign
gen_java analytics Analytics

echo "Java common files generated"
