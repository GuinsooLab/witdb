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
package io.trino.plugin.accumulo;

import com.google.common.collect.ImmutableList;
import io.trino.plugin.accumulo.conf.AccumuloConfig;
import io.trino.plugin.accumulo.conf.AccumuloTableProperties;
import io.trino.plugin.accumulo.index.ColumnCardinalityCache;
import io.trino.plugin.accumulo.index.IndexLookup;
import io.trino.plugin.accumulo.metadata.AccumuloTable;
import io.trino.plugin.accumulo.metadata.ZooKeeperMetadataManager;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaTableName;
import org.apache.accumulo.core.client.Connector;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER;
import static org.testng.Assert.assertNotNull;

public class TestAccumuloClient
{
    private AccumuloClient client;
    private ZooKeeperMetadataManager zooKeeperMetadataManager;

    @BeforeClass
    public void setUp()
            throws Exception
    {
        AccumuloConfig config = new AccumuloConfig()
                .setUsername("root")
                .setPassword("secret");

        Connector connector = TestingAccumuloServer.getInstance().getConnector();
        config.setZooKeepers(connector.getInstance().getZooKeepers());
        zooKeeperMetadataManager = new ZooKeeperMetadataManager(config, TESTING_TYPE_MANAGER);
        client = new AccumuloClient(connector, config, zooKeeperMetadataManager, new AccumuloTableManager(connector), new IndexLookup(connector, new ColumnCardinalityCache(connector, config)));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        zooKeeperMetadataManager = null;
        client = null;
    }

    @Test
    public void testCreateTableEmptyAccumuloColumn()
    {
        SchemaTableName tableName = new SchemaTableName("default", "test_create_table_empty_accumulo_column");

        try {
            List<ColumnMetadata> columns = ImmutableList.of(
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("a", BIGINT),
                    new ColumnMetadata("b", BIGINT),
                    new ColumnMetadata("c", BIGINT),
                    new ColumnMetadata("d", BIGINT));

            Map<String, Object> properties = new HashMap<>();
            new AccumuloTableProperties().getTableProperties().forEach(meta -> properties.put(meta.getName(), meta.getDefaultValue()));
            properties.put("external", true);
            properties.put("column_mapping", "a:a:a,b::b,c:c:,d::");
            client.createTable(new ConnectorTableMetadata(tableName, columns, properties));
            assertNotNull(client.getTable(tableName));
        }
        finally {
            AccumuloTable table = zooKeeperMetadataManager.getTable(tableName);
            if (table != null) {
                client.dropTable(table);
            }
        }
    }
}
