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
package io.trino.plugin.mariadb;

import com.google.common.collect.ImmutableMap;
import io.trino.testing.QueryRunner;
import io.trino.testing.sql.SqlExecutor;

import static io.trino.plugin.mariadb.MariaDbQueryRunner.createMariaDbQueryRunner;
import static io.trino.plugin.mariadb.TestingMariaDbServer.LATEST_VERSION;

public class TestMariaDbLatestConnectorTest
        extends BaseMariaDbConnectorTest
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        server = closeAfterClass(new TestingMariaDbServer(LATEST_VERSION));
        return createMariaDbQueryRunner(server, ImmutableMap.of(), ImmutableMap.of(), REQUIRED_TPCH_TABLES);
    }

    @Override
    protected SqlExecutor onRemoteDatabase()
    {
        return server::execute;
    }
}
