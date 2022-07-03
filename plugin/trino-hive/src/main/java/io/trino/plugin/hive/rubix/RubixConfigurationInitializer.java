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
package io.trino.plugin.hive.rubix;

import io.trino.plugin.hive.DynamicConfigurationProvider;
import io.trino.plugin.hive.HdfsEnvironment.HdfsContext;
import org.apache.hadoop.conf.Configuration;

import javax.inject.Inject;

import java.net.URI;

import static java.util.Objects.requireNonNull;

public class RubixConfigurationInitializer
        implements DynamicConfigurationProvider
{
    private final RubixInitializer rubixInitializer;

    @Inject
    public RubixConfigurationInitializer(RubixInitializer rubixInitializer)
    {
        this.rubixInitializer = requireNonNull(rubixInitializer, "rubixInitializer is null");
    }

    @Override
    public void updateConfiguration(Configuration config, HdfsContext context, URI uri)
    {
        rubixInitializer.enableRubix(config);
    }
}
