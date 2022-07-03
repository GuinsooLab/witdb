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
package io.trino.plugin.druid;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.testing.QueryRunner;

import static io.trino.tpch.TpchTable.CUSTOMER;
import static io.trino.tpch.TpchTable.LINE_ITEM;
import static io.trino.tpch.TpchTable.NATION;
import static io.trino.tpch.TpchTable.ORDERS;
import static io.trino.tpch.TpchTable.PART;
import static io.trino.tpch.TpchTable.REGION;

public class TestDruidLatestConnectorTest
        extends BaseDruidConnectorTest
{
    private static final String LATEST_DRUID_DOCKER_IMAGE = "apache/druid:0.20.0";

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        this.druidServer = new TestingDruidServer(LATEST_DRUID_DOCKER_IMAGE);
        return DruidQueryRunner.createDruidQueryRunnerTpch(
                druidServer,
                ImmutableMap.of(),
                ImmutableList.of(ORDERS, LINE_ITEM, NATION, REGION, PART, CUSTOMER));
    }
}
