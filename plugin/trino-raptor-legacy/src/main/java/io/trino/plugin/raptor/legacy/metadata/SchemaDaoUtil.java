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
package io.trino.plugin.raptor.legacy.metadata;

import io.airlift.log.Logger;
import io.airlift.units.Duration;
import org.jdbi.v3.core.ConnectionException;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.concurrent.TimeUnit;

public final class SchemaDaoUtil
{
    private static final Logger log = Logger.get(SchemaDaoUtil.class);

    private SchemaDaoUtil() {}

    public static void createTablesWithRetry(Jdbi dbi)
    {
        Duration delay = new Duration(2, TimeUnit.SECONDS);
        while (true) {
            try (Handle handle = dbi.open()) {
                createTables(handle.attach(SchemaDao.class));
                return;
            }
            catch (ConnectionException e) {
                log.warn("Failed to connect to database. Will retry again in %s. Exception: %s", delay, e.getMessage());
                sleep(delay);
            }
        }
    }

    private static void createTables(SchemaDao dao)
    {
        dao.createTableDistributions();
        dao.createTableTables();
        dao.createTableColumns();
        dao.createTableViews();
        dao.createTableNodes();
        dao.createTableShards();
        dao.createTableShardNodes();
        dao.createTableExternalBatches();
        dao.createTableTransactions();
        dao.createTableCreatedShards();
        dao.createTableDeletedShards();
        dao.createTableBuckets();
        dao.createTableShardOrganizerJobs();
    }

    private static void sleep(Duration duration)
    {
        try {
            Thread.sleep(duration.toMillis());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
