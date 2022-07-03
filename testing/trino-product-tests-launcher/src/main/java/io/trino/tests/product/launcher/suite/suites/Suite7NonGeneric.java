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
package io.trino.tests.product.launcher.suite.suites;

import com.google.common.collect.ImmutableList;
import io.trino.tests.product.launcher.env.EnvironmentConfig;
import io.trino.tests.product.launcher.env.EnvironmentDefaults;
import io.trino.tests.product.launcher.env.environment.EnvMultinodeClickhouse;
import io.trino.tests.product.launcher.env.environment.EnvMultinodeKerberosKudu;
import io.trino.tests.product.launcher.env.environment.EnvMultinodeMariadb;
import io.trino.tests.product.launcher.env.environment.EnvSinglenodeHiveIcebergRedirections;
import io.trino.tests.product.launcher.env.environment.EnvSinglenodeKerberosHdfsImpersonationCrossRealm;
import io.trino.tests.product.launcher.env.environment.EnvSinglenodeMysql;
import io.trino.tests.product.launcher.env.environment.EnvSinglenodePostgresql;
import io.trino.tests.product.launcher.env.environment.EnvSinglenodeSparkHive;
import io.trino.tests.product.launcher.env.environment.EnvSinglenodeSparkIceberg;
import io.trino.tests.product.launcher.env.environment.EnvSinglenodeSqlserver;
import io.trino.tests.product.launcher.env.environment.EnvTwoKerberosHives;
import io.trino.tests.product.launcher.env.environment.EnvTwoMixedHives;
import io.trino.tests.product.launcher.suite.Suite;
import io.trino.tests.product.launcher.suite.SuiteTestRun;

import java.util.List;

import static com.google.common.base.Verify.verify;
import static io.trino.tests.product.launcher.suite.SuiteTestRun.testOnEnvironment;

public class Suite7NonGeneric
        extends Suite
{
    @Override
    public List<SuiteTestRun> getTestRuns(EnvironmentConfig config)
    {
        verify(config.getHadoopBaseImage().equals(EnvironmentDefaults.HADOOP_BASE_IMAGE), "The suite should be run with default HADOOP_BASE_IMAGE. Leave HADOOP_BASE_IMAGE unset.");

        return ImmutableList.of(
                testOnEnvironment(EnvSinglenodeMysql.class)
                        .withGroups("configured_features", "mysql")
                        .build(),
                testOnEnvironment(EnvMultinodeMariadb.class)
                        .withGroups("configured_features", "mariadb")
                        .build(),
                testOnEnvironment(EnvSinglenodePostgresql.class)
                        .withGroups("configured_features", "postgresql")
                        .build(),
                testOnEnvironment(EnvSinglenodeSqlserver.class)
                        .withGroups("configured_features", "sqlserver")
                        .build(),
                testOnEnvironment(EnvMultinodeClickhouse.class)
                        .withGroups("configured_features", "clickhouse")
                        .build(),
                testOnEnvironment(EnvSinglenodeSparkHive.class)
                        .withGroups("configured_features", "hive_spark")
                        .build(),
                testOnEnvironment(EnvSinglenodeSparkIceberg.class)
                        .withGroups("configured_features", "iceberg")
                        .withExcludedGroups("storage_formats")
                        .build(),
                testOnEnvironment(EnvSinglenodeHiveIcebergRedirections.class)
                        .withGroups("configured_features", "hive_iceberg_redirections")
                        .build(),
                testOnEnvironment(EnvSinglenodeKerberosHdfsImpersonationCrossRealm.class)
                        .withGroups("configured_features", "storage_formats", "cli", "hdfs_impersonation")
                        .build(),
                testOnEnvironment(EnvMultinodeKerberosKudu.class)
                        .withGroups("configured_features", "kudu")
                        .build(),
                testOnEnvironment(EnvTwoMixedHives.class)
                        .withGroups("configured_features", "two_hives")
                        .build(),
                testOnEnvironment(EnvTwoKerberosHives.class)
                        .withGroups("configured_features", "two_hives")
                        .build());
    }
}
