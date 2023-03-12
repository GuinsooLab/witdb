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
package io.trino.sql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import javax.annotation.concurrent.Immutable;

import static io.airlift.slice.SizeOf.estimatedSizeOf;
import static io.airlift.slice.SizeOf.instanceSize;
import static java.util.Objects.requireNonNull;

@Immutable
public class PlanNodeId
{
    private static final int INSTANCE_SIZE = instanceSize(PlanNodeId.class);

    private final String id;

    @JsonCreator
    public PlanNodeId(String id)
    {
        requireNonNull(id, "id is null");
        this.id = id;
    }

    @Override
    @JsonValue
    public String toString()
    {
        return id;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PlanNodeId that = (PlanNodeId) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode()
    {
        return id.hashCode();
    }

    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE
                + estimatedSizeOf(id);
    }
}
