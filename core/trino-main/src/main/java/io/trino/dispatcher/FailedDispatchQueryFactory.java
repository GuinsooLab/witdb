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
package io.trino.dispatcher;

import io.trino.Session;
import io.trino.event.QueryMonitor;
import io.trino.execution.LocationFactory;
import io.trino.server.BasicQueryInfo;
import io.trino.spi.resourcegroups.ResourceGroupId;

import javax.inject.Inject;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static io.trino.util.Failures.toFailure;
import static java.util.Objects.requireNonNull;

public class FailedDispatchQueryFactory
{
    private final QueryMonitor queryMonitor;
    private final LocationFactory locationFactory;
    private final ExecutorService executor;

    @Inject
    public FailedDispatchQueryFactory(QueryMonitor queryMonitor, LocationFactory locationFactory, DispatchExecutor dispatchExecutor)
    {
        this.queryMonitor = requireNonNull(queryMonitor, "queryMonitor is null");
        this.locationFactory = requireNonNull(locationFactory, "locationFactory is null");
        this.executor = requireNonNull(dispatchExecutor, "dispatchExecutor is null").getExecutor();
    }

    public FailedDispatchQuery createFailedDispatchQuery(Session session, String query, Optional<String> preparedQuery, Optional<ResourceGroupId> resourceGroup, Throwable throwable)
    {
        FailedDispatchQuery failedDispatchQuery = new FailedDispatchQuery(
                session,
                query,
                preparedQuery,
                locationFactory.createQueryLocation(session.getQueryId()),
                resourceGroup,
                throwable,
                executor);

        BasicQueryInfo queryInfo = failedDispatchQuery.getBasicQueryInfo();

        queryMonitor.queryCreatedEvent(queryInfo);
        queryMonitor.queryImmediateFailureEvent(queryInfo, toFailure(throwable));

        return failedDispatchQuery;
    }
}
