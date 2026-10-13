package com.myplus.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * RW-0c (V26) — which permission a request to the monolith needs: method + path prefix → code. The monolith's
 * PermissionInterceptor reads these rows; the most specific rule has the lowest sort order and wins.
 */
@Entity
@Table(name = "endpoint_permission")
@Getter @Setter @NoArgsConstructor
public class EndpointPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "http_method", nullable = false, length = 8)
    private String httpMethod;

    @Column(name = "path_prefix", nullable = false, length = 128)
    private String pathPrefix;

    @Column(name = "permission_code", nullable = false, length = 64)
    private String permissionCode;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
