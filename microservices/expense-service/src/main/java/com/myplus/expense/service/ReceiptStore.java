package com.myplus.expense.service;

/**
 * EX-5 — the port receipts are kept behind (Ports and Adapters). A local disk volume now ({@link LocalFsReceiptStore});
 * an S3 adapter when AWS lands (ruling R-3), with no change to the service. Keys are {@code org/{id}/…}, built by the
 * service — an adapter never takes a key from a request.
 */
public interface ReceiptStore {

    void put(String key, byte[] bytes);

    byte[] get(String key);

    /** Only for receipts that were never evidence (uploaded, never attached). */
    void delete(String key);
}
