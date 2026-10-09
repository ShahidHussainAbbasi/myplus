package com.myplus.expense.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.service.ReceiptService;
import com.myplus.expense.service.ReceiptService.ReceiptView;

import lombok.RequiredArgsConstructor;

/** EX-5 — receipts. Scope and rules live in {@link ReceiptService}. */
@RestController
@RequestMapping("/api/expense")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService receipts;

    /** Upload a receipt: for an expense about to be saved (no voucherId), or for one already saved. */
    @PostMapping(value = "/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ReceiptView> upload(@RequestPart("file") MultipartFile file,
                                           @RequestParam(value = "voucherId", required = false) Long voucherId) {
        try {
            return ApiResponse.success(receipts.upload(file.getBytes(), file.getOriginalFilename(), voucherId), "Receipt kept");
        } catch (IOException e) {
            throw new ValidationException("The receipt could not be read. Try again.");
        }
    }

    @GetMapping("/vouchers/{id}/receipts")
    public ApiResponse<List<ReceiptView>> list(@PathVariable Long id) {
        return ApiResponse.success(receipts.list(id));
    }

    /**
     * The file itself, shown inline. nosniff + the type WE recognised (never the uploader's word), private and not
     * cached: a receipt is a business's own document.
     */
    @GetMapping("/receipts/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        ReceiptService.Content c = receipts.content(id);
        String name = c.name() == null ? "receipt" : c.name();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(c.bytes());
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @DeleteMapping("/receipts/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        receipts.remove(id);
        return ApiResponse.success(null, "Receipt removed");
    }
}
