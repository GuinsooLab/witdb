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
package io.trino.execution.scheduler;

import io.trino.metadata.InternalNode;
import io.trino.metadata.Split;

import java.util.Optional;
import java.util.function.ToIntFunction;

import static java.util.Objects.requireNonNull;

public abstract class BucketNodeMap
{
    private final ToIntFunction<Split> splitToBucket;

    public BucketNodeMap(ToIntFunction<Split> splitToBucket)
    {
        this.splitToBucket = requireNonNull(splitToBucket, "splitToBucket is null");
    }

    public abstract int getBucketCount();

    public abstract int getNodeCount();

    public abstract Optional<InternalNode> getAssignedNode(int bucketedId);

    public abstract void assignBucketToNode(int bucketedId, InternalNode node);

    public abstract boolean isDynamic();

    public final Optional<InternalNode> getAssignedNode(Split split)
    {
        return getAssignedNode(splitToBucket.applyAsInt(split));
    }

    public final int getBucket(Split split)
    {
        return splitToBucket.applyAsInt(split);
    }
}
