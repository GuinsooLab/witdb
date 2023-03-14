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
package io.trino.plugin.deltalake;

import io.trino.plugin.hive.metastore.HiveMetastore;

import java.io.File;

import static io.trino.plugin.hive.metastore.file.FileHiveMetastore.createTestingFileHiveMetastore;

public class TestDeltaLakeSharedFileMetastoreViews
        extends BaseDeltaLakeSharedMetastoreViewsTest
{
    @Override
    protected HiveMetastore createTestMetastore(String dataDirectory)
    {
        return createTestingFileHiveMetastore(new File(dataDirectory));
    }
}
