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
package io.trino.plugin.cassandra;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.DefaultProtocolVersion;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.ConfigSecuritySensitive;
import io.airlift.configuration.DefunctConfig;
import io.airlift.configuration.validation.FileExists;
import io.airlift.units.Duration;
import io.airlift.units.MaxDuration;
import io.airlift.units.MinDuration;

import javax.annotation.Nullable;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

@DefunctConfig({"cassandra.thrift-port", "cassandra.partitioner", "cassandra.thrift-connection-factory-class", "cassandra.transport-factory-options",
        "cassandra.no-host-available-retry-count", "cassandra.max-schema-refresh-threads", "cassandra.schema-cache-ttl",
        "cassandra.schema-refresh-interval", "cassandra.load-policy.use-white-list", "cassandra.load-policy.white-list.addresses",
        "cassandra.load-policy.use-token-aware", "cassandra.load-policy.token-aware.shuffle-replicas", "cassandra.load-policy.allowed-addresses"})
public class CassandraClientConfig
{
    private static final Splitter SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    private ConsistencyLevel consistencyLevel = ConsistencyLevel.ONE;
    private int fetchSize = 5_000;
    private List<String> contactPoints = ImmutableList.of();
    private int nativeProtocolPort = 9042;
    private int partitionSizeForBatchSelect = 100;
    private int splitSize = 1_024;
    private int batchSize = 100;
    private Long splitsPerNode;
    private boolean allowDropTable;
    private String username;
    private String password;
    private Duration clientReadTimeout = new Duration(12_000, MILLISECONDS);
    private Duration clientConnectTimeout = new Duration(5_000, MILLISECONDS);
    private Integer clientSoLinger;
    private RetryPolicyType retryPolicy = RetryPolicyType.DEFAULT;
    private boolean useDCAware = true;
    private String dcAwareLocalDC;
    private int dcAwareUsedHostsPerRemoteDc;
    private boolean dcAwareAllowRemoteDCsForLocal;
    private Duration noHostAvailableRetryTimeout = new Duration(1, MINUTES);
    private Optional<Integer> speculativeExecutionLimit = Optional.empty();
    private Duration speculativeExecutionDelay = new Duration(500, MILLISECONDS);
    private ProtocolVersion protocolVersion;
    private boolean tlsEnabled;
    private File keystorePath;
    private String keystorePassword;
    private File truststorePath;
    private String truststorePassword;

    @NotNull
    @Size(min = 1)
    public List<String> getContactPoints()
    {
        return contactPoints;
    }

    @Config("cassandra.contact-points")
    public CassandraClientConfig setContactPoints(String commaSeparatedList)
    {
        this.contactPoints = SPLITTER.splitToList(commaSeparatedList);
        return this;
    }

    public CassandraClientConfig setContactPoints(String... contactPoints)
    {
        this.contactPoints = Arrays.asList(contactPoints);
        return this;
    }

    @Min(1)
    public int getNativeProtocolPort()
    {
        return nativeProtocolPort;
    }

    @Config(("cassandra.native-protocol-port"))
    public CassandraClientConfig setNativeProtocolPort(int nativeProtocolPort)
    {
        this.nativeProtocolPort = nativeProtocolPort;
        return this;
    }

    @NotNull
    public ConsistencyLevel getConsistencyLevel()
    {
        return consistencyLevel;
    }

    @Config("cassandra.consistency-level")
    public CassandraClientConfig setConsistencyLevel(DefaultConsistencyLevel level)
    {
        this.consistencyLevel = level;
        return this;
    }

    @Min(1)
    public int getFetchSize()
    {
        return fetchSize;
    }

    @Config("cassandra.fetch-size")
    public CassandraClientConfig setFetchSize(int fetchSize)
    {
        this.fetchSize = fetchSize;
        return this;
    }

    @Min(1)
    public int getPartitionSizeForBatchSelect()
    {
        return partitionSizeForBatchSelect;
    }

    @Config("cassandra.partition-size-for-batch-select")
    public CassandraClientConfig setPartitionSizeForBatchSelect(int partitionSizeForBatchSelect)
    {
        this.partitionSizeForBatchSelect = partitionSizeForBatchSelect;
        return this;
    }

    @Min(1)
    public int getSplitSize()
    {
        return splitSize;
    }

    @Config("cassandra.split-size")
    public CassandraClientConfig setSplitSize(int splitSize)
    {
        this.splitSize = splitSize;
        return this;
    }

    @Min(1)
    public int getBatchSize()
    {
        return batchSize;
    }

    @Config("cassandra.batch-size")
    public CassandraClientConfig setBatchSize(int batchSize)
    {
        this.batchSize = batchSize;
        return this;
    }

    public Optional<Long> getSplitsPerNode()
    {
        return Optional.ofNullable(splitsPerNode);
    }

    @Config("cassandra.splits-per-node")
    public CassandraClientConfig setSplitsPerNode(Long splitsPerNode)
    {
        this.splitsPerNode = splitsPerNode;
        return this;
    }

    public boolean getAllowDropTable()
    {
        return this.allowDropTable;
    }

    @Config("cassandra.allow-drop-table")
    @ConfigDescription("Allow Cassandra connector to drop table")
    public CassandraClientConfig setAllowDropTable(boolean allowDropTable)
    {
        this.allowDropTable = allowDropTable;
        return this;
    }

    public String getUsername()
    {
        return username;
    }

    @Config("cassandra.username")
    public CassandraClientConfig setUsername(String username)
    {
        this.username = username;
        return this;
    }

    public String getPassword()
    {
        return password;
    }

    @Config("cassandra.password")
    @ConfigSecuritySensitive
    public CassandraClientConfig setPassword(String password)
    {
        this.password = password;
        return this;
    }

    @MinDuration("1ms")
    @MaxDuration("1h")
    public Duration getClientReadTimeout()
    {
        return clientReadTimeout;
    }

    @Config("cassandra.client.read-timeout")
    public CassandraClientConfig setClientReadTimeout(Duration clientReadTimeout)
    {
        this.clientReadTimeout = clientReadTimeout;
        return this;
    }

    @MinDuration("1ms")
    @MaxDuration("1h")
    public Duration getClientConnectTimeout()
    {
        return clientConnectTimeout;
    }

    @Config("cassandra.client.connect-timeout")
    public CassandraClientConfig setClientConnectTimeout(Duration clientConnectTimeout)
    {
        this.clientConnectTimeout = clientConnectTimeout;
        return this;
    }

    @Min(0)
    public Integer getClientSoLinger()
    {
        return clientSoLinger;
    }

    @Config("cassandra.client.so-linger")
    public CassandraClientConfig setClientSoLinger(Integer clientSoLinger)
    {
        this.clientSoLinger = clientSoLinger;
        return this;
    }

    @NotNull
    public RetryPolicyType getRetryPolicy()
    {
        return retryPolicy;
    }

    @Config("cassandra.retry-policy")
    public CassandraClientConfig setRetryPolicy(RetryPolicyType retryPolicy)
    {
        this.retryPolicy = retryPolicy;
        return this;
    }

    public boolean isUseDCAware()
    {
        return this.useDCAware;
    }

    @Config("cassandra.load-policy.use-dc-aware")
    public CassandraClientConfig setUseDCAware(boolean useDCAware)
    {
        this.useDCAware = useDCAware;
        return this;
    }

    public String getDcAwareLocalDC()
    {
        return dcAwareLocalDC;
    }

    @Config("cassandra.load-policy.dc-aware.local-dc")
    public CassandraClientConfig setDcAwareLocalDC(String dcAwareLocalDC)
    {
        this.dcAwareLocalDC = dcAwareLocalDC;
        return this;
    }

    @Min(0)
    public Integer getDcAwareUsedHostsPerRemoteDc()
    {
        return dcAwareUsedHostsPerRemoteDc;
    }

    @Config("cassandra.load-policy.dc-aware.used-hosts-per-remote-dc")
    public CassandraClientConfig setDcAwareUsedHostsPerRemoteDc(Integer dcAwareUsedHostsPerRemoteDc)
    {
        this.dcAwareUsedHostsPerRemoteDc = dcAwareUsedHostsPerRemoteDc;
        return this;
    }

    public boolean isDcAwareAllowRemoteDCsForLocal()
    {
        return this.dcAwareAllowRemoteDCsForLocal;
    }

    @Config("cassandra.load-policy.dc-aware.allow-remote-dc-for-local")
    public CassandraClientConfig setDcAwareAllowRemoteDCsForLocal(boolean dcAwareAllowRemoteDCsForLocal)
    {
        this.dcAwareAllowRemoteDCsForLocal = dcAwareAllowRemoteDCsForLocal;
        return this;
    }

    @NotNull
    public Duration getNoHostAvailableRetryTimeout()
    {
        return noHostAvailableRetryTimeout;
    }

    @Config("cassandra.no-host-available-retry-timeout")
    public CassandraClientConfig setNoHostAvailableRetryTimeout(Duration noHostAvailableRetryTimeout)
    {
        this.noHostAvailableRetryTimeout = noHostAvailableRetryTimeout;
        return this;
    }

    public Optional<@Min(1) Integer> getSpeculativeExecutionLimit()
    {
        return speculativeExecutionLimit;
    }

    @Config("cassandra.speculative-execution.limit")
    public CassandraClientConfig setSpeculativeExecutionLimit(Integer speculativeExecutionLimit)
    {
        this.speculativeExecutionLimit = Optional.ofNullable(speculativeExecutionLimit);
        return this;
    }

    @MinDuration("1ms")
    public Duration getSpeculativeExecutionDelay()
    {
        return speculativeExecutionDelay;
    }

    @Config("cassandra.speculative-execution.delay")
    public CassandraClientConfig setSpeculativeExecutionDelay(Duration speculativeExecutionDelay)
    {
        this.speculativeExecutionDelay = speculativeExecutionDelay;
        return this;
    }

    @Nullable
    public ProtocolVersion getProtocolVersion()
    {
        return protocolVersion;
    }

    @Config("cassandra.protocol-version")
    public CassandraClientConfig setProtocolVersion(DefaultProtocolVersion version)
    {
        this.protocolVersion = version;
        return this;
    }

    public boolean isTlsEnabled()
    {
        return tlsEnabled;
    }

    @Config("cassandra.tls.enabled")
    public CassandraClientConfig setTlsEnabled(boolean tlsEnabled)
    {
        this.tlsEnabled = tlsEnabled;
        return this;
    }

    public Optional<@FileExists File> getKeystorePath()
    {
        return Optional.ofNullable(keystorePath);
    }

    @Config("cassandra.tls.keystore-path")
    public CassandraClientConfig setKeystorePath(File keystorePath)
    {
        this.keystorePath = keystorePath;
        return this;
    }

    public Optional<String> getKeystorePassword()
    {
        return Optional.ofNullable(keystorePassword);
    }

    @Config("cassandra.tls.keystore-password")
    @ConfigSecuritySensitive
    public CassandraClientConfig setKeystorePassword(String keystorePassword)
    {
        this.keystorePassword = keystorePassword;
        return this;
    }

    public Optional<@FileExists File> getTruststorePath()
    {
        return Optional.ofNullable(truststorePath);
    }

    @Config("cassandra.tls.truststore-path")
    public CassandraClientConfig setTruststorePath(File truststorePath)
    {
        this.truststorePath = truststorePath;
        return this;
    }

    public Optional<String> getTruststorePassword()
    {
        return Optional.ofNullable(truststorePassword);
    }

    @Config("cassandra.tls.truststore-password")
    @ConfigSecuritySensitive
    public CassandraClientConfig setTruststorePassword(String truststorePassword)
    {
        this.truststorePassword = truststorePassword;
        return this;
    }
}
