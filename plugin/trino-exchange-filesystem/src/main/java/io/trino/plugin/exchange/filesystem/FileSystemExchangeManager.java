/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.exchange.filesystem;

import com.google.common.collect.ImmutableList;
import io.trino.spi.TrinoException;
import io.trino.spi.exchange.Exchange;
import io.trino.spi.exchange.ExchangeContext;
import io.trino.spi.exchange.ExchangeManager;
import io.trino.spi.exchange.ExchangeSink;
import io.trino.spi.exchange.ExchangeSinkInstanceHandle;
import io.trino.spi.exchange.ExchangeSource;
import io.trino.spi.exchange.ExchangeSourceHandle;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;

import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.trino.plugin.exchange.filesystem.FileSystemExchangeErrorCode.MAX_OUTPUT_PARTITION_COUNT_EXCEEDED;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class FileSystemExchangeManager
        implements ExchangeManager
{
    public static final String PATH_SEPARATOR = "/";

    private static final int KEY_BITS = 256;

    private final FileSystemExchangeStorage exchangeStorage;
    private final FileSystemExchangeStats stats;
    private final List<URI> baseDirectories;
    private final boolean exchangeEncryptionEnabled;
    private final int maxPageStorageSizeInBytes;
    private final int exchangeSinkBufferPoolMinSize;
    private final int exchangeSinkBuffersPerPartition;
    private final long exchangeSinkMaxFileSizeInBytes;
    private final int exchangeSourceConcurrentReaders;
    private final int maxOutputPartitionCount;
    private final int exchangeFileListingParallelism;
    private final ExecutorService executor;

    @Inject
    public FileSystemExchangeManager(
            FileSystemExchangeStorage exchangeStorage,
            FileSystemExchangeStats stats,
            FileSystemExchangeConfig fileSystemExchangeConfig)
    {
        requireNonNull(fileSystemExchangeConfig, "fileSystemExchangeConfig is null");

        this.exchangeStorage = requireNonNull(exchangeStorage, "exchangeStorage is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.baseDirectories = ImmutableList.copyOf(requireNonNull(fileSystemExchangeConfig.getBaseDirectories(), "baseDirectories is null"));
        this.exchangeEncryptionEnabled = fileSystemExchangeConfig.isExchangeEncryptionEnabled();
        this.maxPageStorageSizeInBytes = toIntExact(fileSystemExchangeConfig.getMaxPageStorageSize().toBytes());
        this.exchangeSinkBufferPoolMinSize = fileSystemExchangeConfig.getExchangeSinkBufferPoolMinSize();
        this.exchangeSinkBuffersPerPartition = fileSystemExchangeConfig.getExchangeSinkBuffersPerPartition();
        this.exchangeSinkMaxFileSizeInBytes = fileSystemExchangeConfig.getExchangeSinkMaxFileSize().toBytes();
        this.exchangeSourceConcurrentReaders = fileSystemExchangeConfig.getExchangeSourceConcurrentReaders();
        this.maxOutputPartitionCount = fileSystemExchangeConfig.getMaxOutputPartitionCount();
        this.exchangeFileListingParallelism = fileSystemExchangeConfig.getExchangeFileListingParallelism();
        this.executor = newCachedThreadPool(daemonThreadsNamed("exchange-source-handles-creation-%s"));
    }

    @Override
    public Exchange createExchange(ExchangeContext context, int outputPartitionCount)
    {
        if (outputPartitionCount > maxOutputPartitionCount) {
            throw new TrinoException(
                    MAX_OUTPUT_PARTITION_COUNT_EXCEEDED,
                    format("Max number of output partitions exceeded for exchange '%s'. Allowed: %s. Requested: %s.", context.getExchangeId(), maxOutputPartitionCount, outputPartitionCount));
        }

        Optional<SecretKey> secretKey = Optional.empty();
        if (exchangeEncryptionEnabled) {
            try {
                KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
                keyGenerator.init(KEY_BITS);
                secretKey = Optional.of(keyGenerator.generateKey());
            }
            catch (NoSuchAlgorithmException e) {
                throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to generate new secret key: " + e.getMessage(), e);
            }
        }
        return new FileSystemExchange(
                baseDirectories,
                exchangeStorage,
                stats,
                context,
                outputPartitionCount,
                exchangeFileListingParallelism,
                secretKey,
                executor);
    }

    @Override
    public ExchangeSink createSink(ExchangeSinkInstanceHandle handle, boolean preserveRecordsOrder)
    {
        FileSystemExchangeSinkInstanceHandle instanceHandle = (FileSystemExchangeSinkInstanceHandle) handle;
        return new FileSystemExchangeSink(
                exchangeStorage,
                stats,
                instanceHandle.getOutputDirectory(),
                instanceHandle.getOutputPartitionCount(),
                instanceHandle.getSinkHandle().getSecretKey().map(key -> new SecretKeySpec(key, 0, key.length, "AES")),
                preserveRecordsOrder,
                maxPageStorageSizeInBytes,
                exchangeSinkBufferPoolMinSize,
                exchangeSinkBuffersPerPartition,
                exchangeSinkMaxFileSizeInBytes);
    }

    @Override
    public ExchangeSource createSource(List<ExchangeSourceHandle> handles)
    {
        List<ExchangeSourceFile> sourceFiles = handles.stream()
                .map(FileSystemExchangeSourceHandle.class::cast)
                .map(handle -> {
                    Optional<SecretKey> secretKey = handle.getSecretKey().map(key -> new SecretKeySpec(key, 0, key.length, "AES"));
                    return new AbstractMap.SimpleEntry<>(handle, secretKey);
                })
                .flatMap(entry -> entry.getKey().getFiles().stream().map(fileStatus ->
                        new ExchangeSourceFile(
                                URI.create(fileStatus.getFilePath()),
                                entry.getValue(),
                                fileStatus.getFileSize())))
                .collect(toImmutableList());
        return new FileSystemExchangeSource(
                exchangeStorage,
                stats,
                sourceFiles,
                maxPageStorageSizeInBytes,
                exchangeSourceConcurrentReaders);
    }
}
