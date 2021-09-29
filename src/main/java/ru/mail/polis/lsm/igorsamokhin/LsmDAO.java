package ru.mail.polis.lsm.igorsamokhin;

import ru.mail.polis.lsm.DAO;
import ru.mail.polis.lsm.DAOConfig;
import ru.mail.polis.lsm.Record;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;

@SuppressWarnings("JdkObsolete")
public class LsmDAO implements DAO {
    private static final int MEMORY_LIMIT = 1024 * 1024 * 32;
    private static final String FILE_PREFIX = "SSTable_";

    private final SortedMap<ByteBuffer, Record> memoryStorage = new ConcurrentSkipListMap<>();
    private final List<SSTable> ssTables;
    private final DAOConfig config;
    private int memoryConsumption;

    private Path filePath;

    /**
     * Create DAO object.
     *
     * @param config - objects contains directory with data files
     */
    public LsmDAO(DAOConfig config) throws IOException {
        this.config = config;
        memoryConsumption = 0;

        ssTables = SSTable.loadFromDir(config.dir);
        filePath = getNewFileName();
    }

    private Path getNewFileName() {
        String binary = Long.toBinaryString(ssTables.size());
        int leadingN = 64 - binary.length();

        String builder = "0".repeat(leadingN) + binary;
        String name = FILE_PREFIX.concat(builder);
        return config.dir.resolve(name);
    }

    @Override
    public Iterator<Record> range(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        synchronized (this) {
            try {
                flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Can't flush data", e);
            }
            Iterator<Record> memoryRange = SSTable.getSubMap(memoryStorage, fromKey, toKey).values().iterator();
            Iterator<Record> sstableRanges = sstableRanges(fromKey, toKey);
            return LsmDAO.merge(List.of(sstableRanges, memoryRange));
        }
    }

    @Override
    public void upsert(Record record) {
        synchronized (this) {
            int size = sizeOf(record);
            if (memoryConsumption + size > MEMORY_LIMIT) {
                try {
                    flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            memoryConsumption += size;
            memoryStorage.put(record.getKey(), record);
        }
    }

    private int sizeOf(Record record) {
        int keyCapacity = record.getKey().capacity();
        ByteBuffer value = record.getValue();
        int valueCapacity = (value == null) ? 0 : value.capacity();
        return keyCapacity + valueCapacity;
    }

    @GuardedBy("this")
    private void flush() throws IOException {
        if (memoryConsumption == 0) {
            return;
        }
        SSTable.write(memoryStorage.values().iterator(), filePath);
        SSTable ssTable = SSTable.loadFromFile(filePath);
        ssTables.add(ssTable);
        memoryStorage.clear();
        memoryConsumption = 0;
        filePath = getNewFileName();
    }

    private Iterator<Record> sstableRanges(@Nullable ByteBuffer fromKey, @Nullable ByteBuffer toKey) {
        List<Iterator<Record>> iterators = new ArrayList<>(ssTables.size());
        for (SSTable sstable : ssTables) {
            iterators.add(sstable.range(fromKey, toKey));
        }
        return LsmDAO.merge(iterators);
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            flush();
            if (ssTables.isEmpty()) {
                return;
            }

            for (SSTable ssTable : ssTables) {
                ssTable.close();
            }
        }
    }

    @Override
    public void closeAndCompact() {
        synchronized (this) {
            Path compactFile = null;
            try {
                compactFile = SSTable.compact(config.dir, this.range(null, null));
            } catch (IOException e) {
                throw new UncheckedIOException("Can't compact", e);
            }

            if (compactFile == null) {
                return;
            }

            for (SSTable ssTable : ssTables) {
                try {
                    ssTable.close();
                } catch (IOException e) {
                    throw new UncheckedIOException("Can't close sstable", e);
                }
            }

            ssTables.clear();

            try {
                ssTables.addAll(SSTable.loadFromDir(config.dir));
            } catch (IOException e) {
                throw new UncheckedIOException("Can't load db from directory", e);
            }

            filePath = getNewFileName();
        }
    }

    /**
     * Merge iterators into one iterator.
     */
    public static Iterator<Record> merge(List<Iterator<Record>> iterators) {
        return new MergingIterator(iterators);
    }
}
