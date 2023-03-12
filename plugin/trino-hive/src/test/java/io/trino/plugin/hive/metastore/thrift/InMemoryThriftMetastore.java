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
package io.trino.plugin.hive.metastore.thrift;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.hive.thrift.metastore.Database;
import io.trino.hive.thrift.metastore.FieldSchema;
import io.trino.hive.thrift.metastore.Partition;
import io.trino.hive.thrift.metastore.PrincipalPrivilegeSet;
import io.trino.hive.thrift.metastore.PrincipalType;
import io.trino.hive.thrift.metastore.Table;
import io.trino.plugin.hive.HiveColumnStatisticType;
import io.trino.plugin.hive.PartitionStatistics;
import io.trino.plugin.hive.SchemaAlreadyExistsException;
import io.trino.plugin.hive.TableAlreadyExistsException;
import io.trino.plugin.hive.acid.AcidTransaction;
import io.trino.plugin.hive.metastore.HivePrincipal;
import io.trino.plugin.hive.metastore.HivePrivilegeInfo;
import io.trino.plugin.hive.metastore.HivePrivilegeInfo.HivePrivilege;
import io.trino.plugin.hive.metastore.PartitionWithStatistics;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.SchemaNotFoundException;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.security.RoleGrant;
import io.trino.spi.type.Type;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.TableType;

import javax.annotation.concurrent.GuardedBy;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.trino.plugin.hive.HiveBasicStatistics.createEmptyStatistics;
import static io.trino.plugin.hive.metastore.MetastoreUtil.partitionKeyFilterToStringList;
import static io.trino.plugin.hive.metastore.thrift.ThriftMetastoreUtil.toMetastoreApiPartition;
import static io.trino.plugin.hive.util.HiveUtil.toPartitionValues;
import static io.trino.spi.StandardErrorCode.SCHEMA_NOT_EMPTY;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hive.common.FileUtils.makePartName;
import static org.apache.hadoop.hive.metastore.TableType.EXTERNAL_TABLE;
import static org.apache.hadoop.hive.metastore.TableType.MANAGED_TABLE;
import static org.apache.hadoop.hive.metastore.TableType.VIRTUAL_VIEW;

public class InMemoryThriftMetastore
        implements ThriftMetastore
{
    @GuardedBy("this")
    private final Map<String, Database> databases = new HashMap<>();
    @GuardedBy("this")
    private final Map<SchemaTableName, Table> relations = new HashMap<>();
    @GuardedBy("this")
    private final Map<SchemaTableName, Table> views = new HashMap<>();
    @GuardedBy("this")
    private final Map<PartitionName, Partition> partitions = new HashMap<>();
    @GuardedBy("this")
    private final Map<SchemaTableName, PartitionStatistics> columnStatistics = new HashMap<>();
    @GuardedBy("this")
    private final Map<PartitionName, PartitionStatistics> partitionColumnStatistics = new HashMap<>();
    @GuardedBy("this")
    private final Map<PrincipalTableKey, Set<HivePrivilegeInfo>> tablePrivileges = new HashMap<>();

    private final File baseDirectory;
    private final boolean assumeCanonicalPartitionKeys;

    public InMemoryThriftMetastore(File baseDirectory, ThriftMetastoreConfig metastoreConfig)
    {
        this.baseDirectory = requireNonNull(baseDirectory, "baseDirectory is null");
        this.assumeCanonicalPartitionKeys = requireNonNull(metastoreConfig).isAssumeCanonicalPartitionKeys();
        checkArgument(!baseDirectory.exists(), "Base directory already exists");
        checkArgument(baseDirectory.mkdirs(), "Could not create base directory");
    }

    @Override
    public synchronized void createDatabase(Database database)
    {
        requireNonNull(database, "database is null");

        File directory;
        if (database.getLocationUri() != null) {
            directory = new File(URI.create(database.getLocationUri()));
        }
        else {
            // use Hive default naming convention
            directory = new File(baseDirectory, database.getName() + ".db");
            database = database.deepCopy();
            database.setLocationUri(directory.toURI().toString());
        }

        checkArgument(!directory.exists(), "Database directory already exists");
        checkArgument(isParentDir(directory, baseDirectory), "Database directory must be inside of the metastore base directory");
        checkArgument(directory.mkdirs(), "Could not create database directory");

        if (databases.putIfAbsent(database.getName(), database) != null) {
            throw new SchemaAlreadyExistsException(database.getName());
        }
    }

    // TODO: respect deleteData
    @Override
    public synchronized void dropDatabase(String databaseName, boolean deleteData)
    {
        if (!databases.containsKey(databaseName)) {
            throw new SchemaNotFoundException(databaseName);
        }
        if (!getAllTables(databaseName).isEmpty()) {
            throw new TrinoException(SCHEMA_NOT_EMPTY, "Schema not empty: " + databaseName);
        }
        databases.remove(databaseName);
    }

    @Override
    public synchronized void alterDatabase(String databaseName, Database newDatabase)
    {
        String newDatabaseName = newDatabase.getName();

        if (databaseName.equals(newDatabaseName)) {
            if (databases.replace(databaseName, newDatabase) == null) {
                throw new SchemaNotFoundException(databaseName);
            }
            return;
        }

        Database database = databases.get(databaseName);
        if (database == null) {
            throw new SchemaNotFoundException(databaseName);
        }
        if (databases.putIfAbsent(newDatabaseName, database) != null) {
            throw new SchemaAlreadyExistsException(newDatabaseName);
        }
        databases.remove(databaseName);

        rewriteKeys(relations, name -> new SchemaTableName(newDatabaseName, name.getTableName()));
        rewriteKeys(views, name -> new SchemaTableName(newDatabaseName, name.getTableName()));
        rewriteKeys(partitions, name -> name.withSchemaName(newDatabaseName));
        rewriteKeys(tablePrivileges, name -> name.withDatabase(newDatabaseName));
    }

    @Override
    public synchronized List<String> getAllDatabases()
    {
        return ImmutableList.copyOf(databases.keySet());
    }

    @Override
    public synchronized void createTable(Table table)
    {
        TableType tableType = TableType.valueOf(table.getTableType());
        checkArgument(EnumSet.of(MANAGED_TABLE, EXTERNAL_TABLE, VIRTUAL_VIEW).contains(tableType), "Invalid table type: %s", tableType);

        if (tableType == VIRTUAL_VIEW) {
            checkArgument(table.getSd().getLocation() == null, "Storage location for view must be null");
        }
        else {
            File directory = new File(new Path(table.getSd().getLocation()).toUri());
            checkArgument(directory.exists(), "Table directory does not exist");
            if (tableType == MANAGED_TABLE) {
                checkArgument(isParentDir(directory, baseDirectory), "Table directory must be inside of the metastore base directory");
            }
        }

        SchemaTableName schemaTableName = new SchemaTableName(table.getDbName(), table.getTableName());
        Table tableCopy = table.deepCopy();

        if (relations.putIfAbsent(schemaTableName, tableCopy) != null) {
            throw new TableAlreadyExistsException(schemaTableName);
        }

        if (tableType == VIRTUAL_VIEW) {
            views.put(schemaTableName, tableCopy);
        }

        PrincipalPrivilegeSet privileges = table.getPrivileges();
        if (privileges != null) {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public synchronized void dropTable(String databaseName, String tableName, boolean deleteData)
    {
        List<String> locations = listAllDataPaths(this, databaseName, tableName);

        SchemaTableName schemaTableName = new SchemaTableName(databaseName, tableName);
        Table table = relations.remove(schemaTableName);
        if (table == null) {
            throw new TableNotFoundException(schemaTableName);
        }
        views.remove(schemaTableName);
        partitions.keySet().removeIf(partitionName -> partitionName.matches(databaseName, tableName));

        // remove data
        if (deleteData && table.getTableType().equals(MANAGED_TABLE.name())) {
            for (String location : locations) {
                if (location != null) {
                    File directory = new File(new Path(location).toUri());
                    checkArgument(isParentDir(directory, baseDirectory), "Table directory must be inside of the metastore base directory");
                    deleteDirectory(directory);
                }
            }
        }
    }

    private static List<String> listAllDataPaths(ThriftMetastore metastore, String schemaName, String tableName)
    {
        ImmutableList.Builder<String> locations = ImmutableList.builder();
        Table table = metastore.getTable(schemaName, tableName).get();
        if (table.getSd().getLocation() != null) {
            // For unpartitioned table, there should be nothing directly under this directory.
            // But including this location in the set makes the directory content assert more
            // extensive, which is desirable.
            locations.add(table.getSd().getLocation());
        }
        List<String> partitionColumnNames = table.getPartitionKeys().stream()
                .map(FieldSchema::getName)
                .collect(toImmutableList());
        Optional<List<String>> partitionNames = metastore.getPartitionNamesByFilter(schemaName, tableName, partitionColumnNames, TupleDomain.all());
        if (partitionNames.isPresent()) {
            metastore.getPartitionsByNames(schemaName, tableName, partitionNames.get()).stream()
                    .map(partition -> partition.getSd().getLocation())
                    .filter(location -> !location.startsWith(table.getSd().getLocation()))
                    .forEach(locations::add);
        }

        return locations.build();
    }

    @Override
    public synchronized void alterTable(String databaseName, String tableName, Table newTable)
    {
        SchemaTableName oldName = new SchemaTableName(databaseName, tableName);
        SchemaTableName newName = new SchemaTableName(newTable.getDbName(), newTable.getTableName());

        // if the name did not change, this is a simple schema change
        if (oldName.equals(newName)) {
            if (relations.replace(oldName, newTable) == null) {
                throw new TableNotFoundException(oldName);
            }
            return;
        }

        // remove old table definition and add the new one
        Table table = relations.get(oldName);
        if (table == null) {
            throw new TableNotFoundException(oldName);
        }

        if (relations.putIfAbsent(newName, newTable) != null) {
            throw new TableAlreadyExistsException(newName);
        }
        relations.remove(oldName);
    }

    @Override
    public void alterTransactionalTable(Table table, long transactionId, long writeId)
    {
        alterTable(table.getDbName(), table.getTableName(), table);
    }

    @Override
    public synchronized List<String> getAllTables(String databaseName)
    {
        ImmutableList.Builder<String> tables = ImmutableList.builder();
        for (SchemaTableName schemaTableName : this.relations.keySet()) {
            if (schemaTableName.getSchemaName().equals(databaseName)) {
                tables.add(schemaTableName.getTableName());
            }
        }
        return tables.build();
    }

    @Override
    public synchronized List<String> getTablesWithParameter(String databaseName, String parameterKey, String parameterValue)
    {
        requireNonNull(parameterKey, "parameterKey is null");
        requireNonNull(parameterValue, "parameterValue is null");

        return relations.entrySet().stream()
                .filter(entry -> entry.getKey().getSchemaName().equals(databaseName)
                        && parameterValue.equals(entry.getValue().getParameters().get(parameterKey)))
                .map(entry -> entry.getKey().getTableName())
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<String> getAllViews(String databaseName)
    {
        ImmutableList.Builder<String> tables = ImmutableList.builder();
        for (SchemaTableName schemaTableName : this.views.keySet()) {
            if (schemaTableName.getSchemaName().equals(databaseName)) {
                tables.add(schemaTableName.getTableName());
            }
        }
        return tables.build();
    }

    @Override
    public synchronized Optional<Database> getDatabase(String databaseName)
    {
        return Optional.ofNullable(databases.get(databaseName));
    }

    @Override
    public synchronized void addPartitions(String databaseName, String tableName, List<PartitionWithStatistics> partitionsWithStatistics)
    {
        for (PartitionWithStatistics partitionWithStatistics : partitionsWithStatistics) {
            Partition partition = toMetastoreApiPartition(partitionWithStatistics.getPartition());
            if (partition.getParameters() == null) {
                partition.setParameters(ImmutableMap.of());
            }
            PartitionName partitionKey = PartitionName.partition(databaseName, tableName, partitionWithStatistics.getPartitionName());
            partitions.put(partitionKey, partition);
            partitionColumnStatistics.put(partitionKey, partitionWithStatistics.getStatistics());
        }
    }

    @Override
    public synchronized void dropPartition(String databaseName, String tableName, List<String> parts, boolean deleteData)
    {
        partitions.entrySet().removeIf(entry ->
                entry.getKey().matches(databaseName, tableName) && entry.getValue().getValues().equals(parts));
    }

    @Override
    public synchronized void alterPartition(String databaseName, String tableName, PartitionWithStatistics partitionWithStatistics)
    {
        Partition partition = toMetastoreApiPartition(partitionWithStatistics.getPartition());
        if (partition.getParameters() == null) {
            partition.setParameters(ImmutableMap.of());
        }
        PartitionName partitionKey = PartitionName.partition(databaseName, tableName, partitionWithStatistics.getPartitionName());
        partitions.put(partitionKey, partition);
        partitionColumnStatistics.put(partitionKey, partitionWithStatistics.getStatistics());
    }

    @Override
    public synchronized Optional<Partition> getPartition(String databaseName, String tableName, List<String> partitionValues)
    {
        PartitionName name = PartitionName.partition(databaseName, tableName, partitionValues);
        Partition partition = partitions.get(name);
        if (partition == null) {
            return Optional.empty();
        }
        return Optional.of(partition.deepCopy());
    }

    @Override
    public synchronized Optional<List<String>> getPartitionNamesByFilter(String databaseName, String tableName, List<String> columnNames, TupleDomain<String> partitionKeysFilter)
    {
        Optional<List<String>> parts = partitionKeyFilterToStringList(columnNames, partitionKeysFilter, assumeCanonicalPartitionKeys);

        if (parts.isEmpty()) {
            return Optional.of(ImmutableList.of());
        }
        return Optional.of(partitions.entrySet().stream()
                .filter(entry -> partitionMatches(entry.getValue(), databaseName, tableName, parts.get()))
                .map(entry -> entry.getKey().getPartitionName())
                .collect(toImmutableList()));
    }

    private static boolean partitionMatches(Partition partition, String databaseName, String tableName, List<String> parts)
    {
        if (!partition.getDbName().equals(databaseName) ||
                !partition.getTableName().equals(tableName)) {
            return false;
        }
        List<String> values = partition.getValues();
        if (values.size() != parts.size()) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            String part = parts.get(i);
            if (!part.isEmpty() && !values.get(i).equals(part)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized List<Partition> getPartitionsByNames(String databaseName, String tableName, List<String> partitionNames)
    {
        ImmutableList.Builder<Partition> builder = ImmutableList.builder();
        for (String name : partitionNames) {
            PartitionName partitionName = PartitionName.partition(databaseName, tableName, name);
            Partition partition = partitions.get(partitionName);
            if (partition == null) {
                return ImmutableList.of();
            }
            builder.add(partition.deepCopy());
        }
        return builder.build();
    }

    @Override
    public synchronized Optional<Table> getTable(String databaseName, String tableName)
    {
        SchemaTableName schemaTableName = new SchemaTableName(databaseName, tableName);
        return Optional.ofNullable(relations.get(schemaTableName));
    }

    @Override
    public Set<HiveColumnStatisticType> getSupportedColumnStatistics(Type type)
    {
        return ThriftMetastoreUtil.getSupportedColumnStatistics(type);
    }

    @Override
    public synchronized PartitionStatistics getTableStatistics(Table table)
    {
        return getTableStatistics(table.getDbName(), table.getTableName());
    }

    private synchronized PartitionStatistics getTableStatistics(String databaseName, String tableName)
    {
        SchemaTableName schemaTableName = new SchemaTableName(databaseName, tableName);
        PartitionStatistics statistics = columnStatistics.get(schemaTableName);
        if (statistics == null) {
            statistics = new PartitionStatistics(createEmptyStatistics(), ImmutableMap.of());
        }
        return statistics;
    }

    @Override
    public synchronized Map<String, PartitionStatistics> getPartitionStatistics(Table table, List<Partition> partitions)
    {
        List<String> partitionColumns = table.getPartitionKeys().stream()
                .map(FieldSchema::getName)
                .collect(toImmutableList());
        Set<String> partitionNames = partitions.stream()
                .map(partition -> makePartName(partitionColumns, partition.getValues()))
                .collect(toImmutableSet());
        return getPartitionStatistics(table.getDbName(), table.getTableName(), partitionNames);
    }

    private synchronized Map<String, PartitionStatistics> getPartitionStatistics(String databaseName, String tableName, Set<String> partitionNames)
    {
        ImmutableMap.Builder<String, PartitionStatistics> result = ImmutableMap.builder();
        for (String partitionName : partitionNames) {
            PartitionName partitionKey = PartitionName.partition(databaseName, tableName, partitionName);
            PartitionStatistics statistics = partitionColumnStatistics.get(partitionKey);
            if (statistics == null) {
                statistics = new PartitionStatistics(createEmptyStatistics(), ImmutableMap.of());
            }
            result.put(partitionName, statistics);
        }
        return result.buildOrThrow();
    }

    @Override
    public synchronized void updateTableStatistics(String databaseName, String tableName, AcidTransaction transaction, Function<PartitionStatistics, PartitionStatistics> update)
    {
        columnStatistics.put(new SchemaTableName(databaseName, tableName), update.apply(getTableStatistics(databaseName, tableName)));
    }

    @Override
    public synchronized void updatePartitionStatistics(Table table, String partitionName, Function<PartitionStatistics, PartitionStatistics> update)
    {
        PartitionName partitionKey = PartitionName.partition(table.getDbName(), table.getTableName(), partitionName);
        partitionColumnStatistics.put(partitionKey, update.apply(getPartitionStatistics(table.getDbName(), table.getTableName(), ImmutableSet.of(partitionName)).get(partitionName)));
    }

    @Override
    public void createRole(String role, String grantor)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropRole(String role)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> listRoles()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void grantRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOption, HivePrincipal grantor)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void revokeRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOption, HivePrincipal grantor)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<RoleGrant> listGrantedPrincipals(String role)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<RoleGrant> listRoleGrants(HivePrincipal principal)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<HivePrivilegeInfo> listTablePrivileges(String databaseName, String tableName, Optional<String> tableOwner, Optional<HivePrincipal> principal)
    {
        return ImmutableSet.of();
    }

    @Override
    public void grantTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee, HivePrincipal grantor, Set<HivePrivilege> privileges, boolean grantOption)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void revokeTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee, HivePrincipal grantor, Set<HivePrivilege> privileges, boolean grantOption)
    {
        throw new UnsupportedOperationException();
    }

    private static boolean isParentDir(File directory, File baseDirectory)
    {
        for (File parent = directory.getParentFile(); parent != null; parent = parent.getParentFile()) {
            if (parent.equals(baseDirectory)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteDirectory(File dir)
    {
        try {
            deleteRecursively(dir.toPath(), ALLOW_INSECURE);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class PartitionName
    {
        private final String schemaName;
        private final String tableName;
        private final List<String> partitionValues;
        private final String partitionName; // does not participate in equals and hashValue

        private PartitionName(String schemaName, String tableName, List<String> partitionValues, String partitionName)
        {
            this.schemaName = schemaName.toLowerCase(US);
            this.tableName = tableName.toLowerCase(US);
            this.partitionValues = requireNonNull(partitionValues, "partitionValues is null");
            this.partitionName = partitionName;
        }

        public static PartitionName partition(String schemaName, String tableName, String partitionName)
        {
            return new PartitionName(schemaName.toLowerCase(US), tableName.toLowerCase(US), toPartitionValues(partitionName), partitionName);
        }

        public static PartitionName partition(String schemaName, String tableName, List<String> partitionValues)
        {
            return new PartitionName(schemaName.toLowerCase(US), tableName.toLowerCase(US), partitionValues, null);
        }

        public String getPartitionName()
        {
            return requireNonNull(partitionName, "partitionName is null");
        }

        public boolean matches(String schemaName, String tableName)
        {
            return this.schemaName.equals(schemaName) &&
                    this.tableName.equals(tableName);
        }

        public PartitionName withSchemaName(String schemaName)
        {
            return new PartitionName(schemaName, tableName, partitionValues, partitionName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(schemaName, tableName, partitionValues);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            PartitionName other = (PartitionName) obj;
            return Objects.equals(this.schemaName, other.schemaName)
                    && Objects.equals(this.tableName, other.tableName)
                    && Objects.equals(this.partitionValues, other.partitionValues);
        }

        @Override
        public String toString()
        {
            return schemaName + "/" + tableName + "/" + partitionName;
        }
    }

    private static class PrincipalTableKey
    {
        private final String principalName;
        private final PrincipalType principalType;
        private final String database;
        private final String table;

        public PrincipalTableKey(String principalName, PrincipalType principalType, String table, String database)
        {
            this.principalName = requireNonNull(principalName, "principalName is null");
            this.principalType = requireNonNull(principalType, "principalType is null");
            this.table = requireNonNull(table, "table is null");
            this.database = requireNonNull(database, "database is null");
        }

        public PrincipalTableKey withDatabase(String database)
        {
            return new PrincipalTableKey(principalName, principalType, table, database);
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PrincipalTableKey that = (PrincipalTableKey) o;
            return Objects.equals(principalName, that.principalName) &&
                    principalType == that.principalType &&
                    Objects.equals(table, that.table) &&
                    Objects.equals(database, that.database);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(principalName, principalType, table, database);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("principalName", principalName)
                    .add("principalType", principalType)
                    .add("table", table)
                    .add("database", database)
                    .toString();
        }
    }

    private static <K, V> void rewriteKeys(Map<K, V> map, Function<K, K> keyRewriter)
    {
        for (K key : ImmutableSet.copyOf(map.keySet())) {
            K newKey = keyRewriter.apply(key);
            if (!newKey.equals(key)) {
                map.put(newKey, map.remove(key));
            }
        }
    }
}
