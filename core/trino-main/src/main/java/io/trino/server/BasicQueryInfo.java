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
package io.trino.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.SessionRepresentation;
import io.trino.execution.QueryInfo;
import io.trino.execution.QueryState;
import io.trino.operator.RetryPolicy;
import io.trino.spi.ErrorCode;
import io.trino.spi.ErrorType;
import io.trino.spi.QueryId;
import io.trino.spi.resourcegroups.QueryType;
import io.trino.spi.resourcegroups.ResourceGroupId;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import java.net.URI;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

/**
 * Lightweight version of QueryInfo. Parts of the web UI depend on the fields
 * being named consistently across these classes.
 */
@Immutable
public class BasicQueryInfo
{
    private final QueryId queryId;
    private final SessionRepresentation session;
    private final Optional<ResourceGroupId> resourceGroupId;
    private final QueryState state;
    private final boolean scheduled;
    private final URI self;
    private final String query;
    private final Optional<String> updateType;
    private final Optional<String> preparedQuery;
    private final BasicQueryStats queryStats;
    private final ErrorType errorType;
    private final ErrorCode errorCode;
    private final Optional<QueryType> queryType;
    private final RetryPolicy retryPolicy;

    @JsonCreator
    public BasicQueryInfo(
            @JsonProperty("queryId") QueryId queryId,
            @JsonProperty("session") SessionRepresentation session,
            @JsonProperty("resourceGroupId") Optional<ResourceGroupId> resourceGroupId,
            @JsonProperty("state") QueryState state,
            @JsonProperty("scheduled") boolean scheduled,
            @JsonProperty("self") URI self,
            @JsonProperty("query") String query,
            @JsonProperty("updateType") Optional<String> updateType,
            @JsonProperty("preparedQuery") Optional<String> preparedQuery,
            @JsonProperty("queryStats") BasicQueryStats queryStats,
            @JsonProperty("errorType") ErrorType errorType,
            @JsonProperty("errorCode") ErrorCode errorCode,
            @JsonProperty("queryType") Optional<QueryType> queryType,
            @JsonProperty("retryPolicy") RetryPolicy retryPolicy)
    {
        this.queryId = requireNonNull(queryId, "queryId is null");
        this.session = requireNonNull(session, "session is null");
        this.resourceGroupId = requireNonNull(resourceGroupId, "resourceGroupId is null");
        this.state = requireNonNull(state, "state is null");
        this.errorType = errorType;
        this.errorCode = errorCode;
        this.scheduled = scheduled;
        this.self = requireNonNull(self, "self is null");
        this.query = requireNonNull(query, "query is null");
        this.updateType = requireNonNull(updateType, "updateType is null");
        this.preparedQuery = requireNonNull(preparedQuery, "preparedQuery is null");
        this.queryStats = requireNonNull(queryStats, "queryStats is null");
        this.queryType = requireNonNull(queryType, "queryType is null");
        this.retryPolicy = requireNonNull(retryPolicy, "retryPolicy is null");
    }

    public BasicQueryInfo(QueryInfo queryInfo)
    {
        this(queryInfo.getQueryId(),
                queryInfo.getSession(),
                queryInfo.getResourceGroupId(),
                queryInfo.getState(),
                queryInfo.isScheduled(),
                queryInfo.getSelf(),
                queryInfo.getQuery(),
                Optional.ofNullable(queryInfo.getUpdateType()),
                queryInfo.getPreparedQuery(),
                new BasicQueryStats(queryInfo.getQueryStats()),
                queryInfo.getErrorType(),
                queryInfo.getErrorCode(),
                queryInfo.getQueryType(),
                queryInfo.getRetryPolicy());
    }

    @JsonProperty
    public QueryId getQueryId()
    {
        return queryId;
    }

    @JsonProperty
    public SessionRepresentation getSession()
    {
        return session;
    }

    @JsonProperty
    public Optional<ResourceGroupId> getResourceGroupId()
    {
        return resourceGroupId;
    }

    @JsonProperty
    public QueryState getState()
    {
        return state;
    }

    @JsonProperty
    public boolean isScheduled()
    {
        return scheduled;
    }

    @JsonProperty
    public URI getSelf()
    {
        return self;
    }

    @JsonProperty
    public String getQuery()
    {
        return query;
    }

    @JsonProperty
    public Optional<String> getUpdateType()
    {
        return updateType;
    }

    @JsonProperty
    public Optional<String> getPreparedQuery()
    {
        return preparedQuery;
    }

    @JsonProperty
    public BasicQueryStats getQueryStats()
    {
        return queryStats;
    }

    @Nullable
    @JsonProperty
    public ErrorType getErrorType()
    {
        return errorType;
    }

    @Nullable
    @JsonProperty
    public ErrorCode getErrorCode()
    {
        return errorCode;
    }

    @JsonProperty
    public Optional<QueryType> getQueryType()
    {
        return queryType;
    }

    @JsonProperty
    public RetryPolicy getRetryPolicy()
    {
        return retryPolicy;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("queryId", queryId)
                .add("state", state)
                .toString();
    }
}
