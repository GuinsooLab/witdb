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
package io.trino.plugin.resourcegroups.db;

import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;
import io.trino.plugin.resourcegroups.ResourceGroupIdTemplate;
import io.trino.plugin.resourcegroups.ResourceGroupSelector;
import io.trino.spi.resourcegroups.ResourceGroupId;
import io.trino.spi.resourcegroups.SelectionContext;
import io.trino.spi.resourcegroups.SelectionCriteria;
import org.jdbi.v3.core.JdbiException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.units.Duration.nanosSince;
import static java.util.Objects.requireNonNull;

public class DbSourceExactMatchSelector
        implements ResourceGroupSelector
{
    private static final Logger log = Logger.get(DbSourceExactMatchSelector.class);
    private static final JsonCodec<ResourceGroupId> resourceGroupIdCodec = JsonCodec.jsonCodec(ResourceGroupId.class);
    private final ResourceGroupsDao dao;
    private final String environment;
    private final AtomicReference<Long> daoOfflineStart = new AtomicReference<>();

    public DbSourceExactMatchSelector(String environment, ResourceGroupsDao dao)
    {
        this.environment = requireNonNull(environment, "environment is null");
        this.dao = requireNonNull(dao, "dao is null");
    }

    @Override
    public Optional<SelectionContext<ResourceGroupIdTemplate>> match(SelectionCriteria criteria)
    {
        if (criteria.getSource().isEmpty()) {
            return Optional.empty();
        }
        try {
            String resourceGroupId = dao.getExactMatchResourceGroup(environment, criteria.getSource().get(), criteria.getQueryType().orElse(""));

            Long start = daoOfflineStart.get();
            if (start != null && daoOfflineStart.compareAndSet(start, null)) {
                log.info("Successfully fetched exact match selectors after %s", nanosSince(start));
            }

            if (resourceGroupId == null) {
                return Optional.empty();
            }

            ResourceGroupId groupId;
            try {
                groupId = resourceGroupIdCodec.fromJson(resourceGroupId);
            }
            catch (IllegalArgumentException e) {
                log.warn("Failed to decode resource group from DB: %s", resourceGroupId);
                return Optional.empty();
            }

            return Optional.of(new SelectionContext<>(groupId, toTemplate(groupId)));
        }
        catch (JdbiException e) {
            if (daoOfflineStart.compareAndSet(null, System.nanoTime())) {
                log.warn(e, "Failed to fetch exact match resource group selectors");
            }

            return Optional.empty();
        }
    }

    private static ResourceGroupIdTemplate toTemplate(ResourceGroupId groupId)
    {
        ResourceGroupIdTemplate template = new ResourceGroupIdTemplate(groupId.toString());
        checkArgument(template.getVariableNames().isEmpty(), "Group ID contains variable references: %s", groupId);
        return template;
    }
}
