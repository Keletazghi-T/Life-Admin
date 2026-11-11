package com.lifeadmin.service;
public interface DocumentStorage {
    String store(byte[] content);
    /** Safe extension selected by a validated backend upload, never by an untrusted path. */
    default String store(byte[] content,String extension) { if("pdf".equals(extension)) return store(content); throw new UnsupportedOperationException("Storage adapter does not support this file type"); }
    byte[] read(String storedFileName);
    enum FileState { AVAILABLE, MISSING, INVALID, UNAVAILABLE }
    /** Lightweight integrity check; does not return a storage key or path to clients. */
    default FileState inspect(String key,long expectedSize,String contentType) { return FileState.UNAVAILABLE; }
    /** Idempotent cleanup: a missing object is already deleted. Throw on failure. */
    void delete(String storedFileName);
}
