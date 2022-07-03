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
package io.trino.plugin.hive;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import io.airlift.units.Duration;
import io.trino.hadoop.SocksSocketFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.net.DNSToSwitchMapping;

import javax.inject.Inject;
import javax.net.SocketFactory;

import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.plugin.hive.util.ConfigurationUtils.copy;
import static io.trino.plugin.hive.util.ConfigurationUtils.readConfiguration;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.fs.CommonConfigurationKeys.IPC_PING_INTERVAL_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_RPC_PROTECTION;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_RPC_SOCKET_FACTORY_CLASS_DEFAULT_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_SOCKS_SERVER_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_TIMEOUT_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_KEY_PROVIDER_CACHE_EXPIRY_MS;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_SOCKET_TIMEOUT_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_DOMAIN_SOCKET_PATH_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_REPLICATION_KEY;

public class HdfsConfigurationInitializer
{
    private final HostAndPort socksProxy;
    private final Duration ipcPingInterval;
    private final Duration dfsTimeout;
    private final Duration dfsConnectTimeout;
    private final int dfsConnectMaxRetries;
    private final int dfsKeyProviderCacheTtlMillis;
    private final String domainSocketPath;
    private final Configuration resourcesConfiguration;
    private final int fileSystemMaxCacheSize;
    private final Set<ConfigurationInitializer> configurationInitializers;
    private final boolean wireEncryptionEnabled;
    private final Integer dfsReplication;

    @VisibleForTesting
    public HdfsConfigurationInitializer(HdfsConfig hdfsConfig)
    {
        this(hdfsConfig, ImmutableSet.of());
    }

    @Inject
    public HdfsConfigurationInitializer(HdfsConfig config, Set<ConfigurationInitializer> configurationInitializers)
    {
        checkArgument(config.getDfsTimeout().toMillis() >= 1, "dfsTimeout must be at least 1 ms");

        this.socksProxy = config.getSocksProxy();
        this.ipcPingInterval = config.getIpcPingInterval();
        this.dfsTimeout = config.getDfsTimeout();
        this.dfsConnectTimeout = config.getDfsConnectTimeout();
        this.dfsConnectMaxRetries = config.getDfsConnectMaxRetries();
        this.dfsKeyProviderCacheTtlMillis = toIntExact(config.getDfsKeyProviderCacheTtl().toMillis());
        this.domainSocketPath = config.getDomainSocketPath();
        this.resourcesConfiguration = readConfiguration(config.getResourceConfigFiles());
        this.fileSystemMaxCacheSize = config.getFileSystemMaxCacheSize();
        this.wireEncryptionEnabled = config.isWireEncryptionEnabled();
        this.dfsReplication = config.getDfsReplication();

        this.configurationInitializers = ImmutableSet.copyOf(requireNonNull(configurationInitializers, "configurationInitializers is null"));
    }

    public void initializeConfiguration(Configuration config)
    {
        copy(resourcesConfiguration, config);

        // this is to prevent dfs client from doing reverse DNS lookups to determine whether nodes are rack local
        config.setClass(NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY, NoOpDNSToSwitchMapping.class, DNSToSwitchMapping.class);

        if (socksProxy != null) {
            config.setClass(HADOOP_RPC_SOCKET_FACTORY_CLASS_DEFAULT_KEY, SocksSocketFactory.class, SocketFactory.class);
            config.set(HADOOP_SOCKS_SERVER_KEY, socksProxy.toString());
        }

        if (domainSocketPath != null) {
            config.setStrings(DFS_DOMAIN_SOCKET_PATH_KEY, domainSocketPath);
        }

        // only enable short circuit reads if domain socket path is properly configured
        if (!config.get(DFS_DOMAIN_SOCKET_PATH_KEY, "").trim().isEmpty()) {
            config.setBooleanIfUnset(HdfsClientConfigKeys.Read.ShortCircuit.KEY, true);
        }

        config.setInt(DFS_CLIENT_SOCKET_TIMEOUT_KEY, toIntExact(dfsTimeout.toMillis()));
        config.setInt(IPC_PING_INTERVAL_KEY, toIntExact(ipcPingInterval.toMillis()));
        config.setInt(IPC_CLIENT_CONNECT_TIMEOUT_KEY, toIntExact(dfsConnectTimeout.toMillis()));
        config.setInt(IPC_CLIENT_CONNECT_MAX_RETRIES_KEY, dfsConnectMaxRetries);
        config.setInt(DFS_CLIENT_KEY_PROVIDER_CACHE_EXPIRY_MS, dfsKeyProviderCacheTtlMillis);

        if (wireEncryptionEnabled) {
            config.set(HADOOP_RPC_PROTECTION, "privacy");
            config.setBoolean("dfs.encrypt.data.transfer", true);
        }

        config.setInt("fs.cache.max-size", fileSystemMaxCacheSize);

        if (dfsReplication != null) {
            config.setInt(DFS_REPLICATION_KEY, dfsReplication);
        }

        configurationInitializers.forEach(configurationInitializer -> configurationInitializer.initializeConfiguration(config));
    }

    public static class NoOpDNSToSwitchMapping
            implements DNSToSwitchMapping
    {
        @Override
        public List<String> resolve(List<String> names)
        {
            // dfs client expects an empty list as an indication that the host->switch mapping for the given names are not known
            return ImmutableList.of();
        }

        @Override
        public void reloadCachedMappings()
        {
            // no-op
        }

        @Override
        public void reloadCachedMappings(List<String> names)
        {
            // no-op
        }
    }
}
