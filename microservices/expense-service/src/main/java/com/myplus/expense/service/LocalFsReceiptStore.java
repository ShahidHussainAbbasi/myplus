package com.myplus.expense.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * EX-5 — receipts on a disk volume ({@code expense.receipts.dir}, a Docker volume in the stack). Written to a temp file
 * and moved into place, so a crash never leaves half a receipt under its real key. A key that would leave the base
 * directory is refused — the service builds keys, but the adapter does not trust that.
 */
@Component
public class LocalFsReceiptStore implements ReceiptStore {

    private final Path base;

    public LocalFsReceiptStore(@Value("${expense.receipts.dir:/data/receipts}") String dir) {
        this.base = Path.of(dir).toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        Path p = base.resolve(key).normalize();
        if (!p.startsWith(base) || key.contains("..")) throw new IllegalArgumentException("Bad receipt key");
        return p;
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".up-", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not keep the receipt", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Receipt file is missing", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the receipt file", e);
        }
    }
}
