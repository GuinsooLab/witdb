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

package io.trino.plugin.deltalake.transactionlog;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.testng.annotations.Test;

import static io.trino.hadoop.ConfigurationInstantiator.newEmptyConfiguration;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogParser.getMandatoryCurrentVersion;
import static org.testng.Assert.assertEquals;

public class TestTransactionLogParser
{
    @Test
    public void testGetCurrentVersion()
            throws Exception
    {
        Configuration conf = newEmptyConfiguration();
        Path basePath = new Path(getClass().getClassLoader().getResource("databricks").toURI());
        FileSystem filesystem = basePath.getFileSystem(conf);

        assertEquals(getMandatoryCurrentVersion(filesystem, new Path(basePath, "simple_table_without_checkpoint")), 9);
        assertEquals(getMandatoryCurrentVersion(filesystem, new Path(basePath, "simple_table_ending_on_checkpoint")), 10);
        assertEquals(getMandatoryCurrentVersion(filesystem, new Path(basePath, "simple_table_past_checkpoint")), 11);
    }
}
