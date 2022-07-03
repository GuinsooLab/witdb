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
package io.trino.sql.planner.iterative.rule.test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.spi.Plugin;
import io.trino.testing.LocalQueryRunner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.util.List;
import java.util.Optional;

import static io.airlift.testing.Closeables.closeAllRuntimeException;
import static io.trino.sql.planner.iterative.rule.test.RuleTester.defaultRuleTester;

public abstract class BaseRuleTest
{
    private RuleTester tester;
    private final List<Plugin> plugins;

    public BaseRuleTest(Plugin... plugins)
    {
        this.plugins = ImmutableList.copyOf(plugins);
    }

    @BeforeClass
    public final void setUp()
    {
        Optional<LocalQueryRunner> localQueryRunner = createLocalQueryRunner();

        if (localQueryRunner.isPresent()) {
            plugins.forEach(plugin -> localQueryRunner.get().installPlugin(plugin));
            tester = new RuleTester(localQueryRunner.get());
        }
        else {
            tester = defaultRuleTester(plugins, ImmutableMap.of(), Optional.empty());
        }
    }

    protected Optional<LocalQueryRunner> createLocalQueryRunner()
    {
        return Optional.empty();
    }

    @AfterClass(alwaysRun = true)
    public final void tearDown()
    {
        closeAllRuntimeException(tester);
        tester = null;
    }

    protected RuleTester tester()
    {
        return tester;
    }
}
