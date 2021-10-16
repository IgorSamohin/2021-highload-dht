package ru.mail.polis.lsm;

import java.nio.file.Path;

public class DAOConfig {
    public static final int DEFAULT_MEMORY_LIMIT = 16 * 1024 * 1024;
    public static final int DEFAULT_MAX_TABLES = 512;

    public final Path dir;
    public final int memoryLimit;
    public final int maxTables;

    public DAOConfig(Path dir) {
        this(dir, DEFAULT_MEMORY_LIMIT, DEFAULT_MAX_TABLES);
    }

    public DAOConfig(Path dir, int memoryLimit) {
        this(dir, memoryLimit, DEFAULT_MAX_TABLES);
    }

    public DAOConfig(Path dir, int memoryLimit, int maxTables) {
        this.dir = dir;
        this.memoryLimit = memoryLimit;
        this.maxTables = maxTables;
    }
}
