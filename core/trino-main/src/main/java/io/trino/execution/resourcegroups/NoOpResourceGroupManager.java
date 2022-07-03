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
package io.trino.execution.resourcegroups;

import io.trino.execution.ManagedQueryExecution;
import io.trino.server.ResourceGroupInfo;
import io.trino.spi.resourcegroups.ResourceGroupConfigurationManagerFactory;
import io.trino.spi.resourcegroups.ResourceGroupId;
import io.trino.spi.resourcegroups.SelectionContext;
import io.trino.spi.resourcegroups.SelectionCriteria;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Used on workers.
 */
public final class NoOpResourceGroupManager
        implements ResourceGroupManager<Void>
{
    @Override
    public void submit(ManagedQueryExecution queryExecution, SelectionContext<Void> selectionContext, Executor executor)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ResourceGroupInfo> tryGetResourceGroupInfo(ResourceGroupId id)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<List<ResourceGroupInfo>> tryGetPathToRoot(ResourceGroupId id)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addConfigurationManagerFactory(ResourceGroupConfigurationManagerFactory factory)
    {
        // no-op
    }

    @Override
    public void loadConfigurationManager()
    {
        // no-op
    }

    @Override
    public SelectionContext<Void> selectGroup(SelectionCriteria criteria)
    {
        throw new UnsupportedOperationException();
    }
}
