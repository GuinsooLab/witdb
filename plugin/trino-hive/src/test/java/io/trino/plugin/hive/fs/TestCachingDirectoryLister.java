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
package io.trino.plugin.hive.fs;

import io.airlift.units.Duration;
import org.apache.hadoop.fs.Path;
import org.testng.annotations.Test;

import java.util.List;

// some tests may invalidate the whole cache affecting therefore other concurrent tests
@Test(singleThreaded = true)
public class TestCachingDirectoryLister
        extends BaseCachingDirectoryListerTest<CachingDirectoryLister>
{
    @Override
    protected CachingDirectoryLister createDirectoryLister()
    {
        return new CachingDirectoryLister(Duration.valueOf("5m"), 1_000_000L, List.of("tpch.*"));
    }

    @Override
    protected boolean isCached(CachingDirectoryLister directoryLister, Path path)
    {
        return directoryLister.isCached(path);
    }

    @Test
    public void forceTestNgToRespectSingleThreaded()
    {
        // TODO: Remove after updating TestNG to 7.4.0+ (https://github.com/trinodb/trino/issues/8571)
        // TestNG doesn't enforce @Test(singleThreaded = true) when tests are defined in base class. According to
        // https://github.com/cbeust/testng/issues/2361#issuecomment-688393166 a workaround it to add a dummy test to the leaf test class.
    }
}
