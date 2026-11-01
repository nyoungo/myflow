package com.joinsoft.flow.migration;

/**
 * 迁移过程中的可读异常。
 */
public class MigrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
