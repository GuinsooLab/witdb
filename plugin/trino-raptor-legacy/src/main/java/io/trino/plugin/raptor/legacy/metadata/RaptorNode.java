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
package io.trino.plugin.raptor.legacy.metadata;

import static java.util.Objects.requireNonNull;

public class RaptorNode
{
    private final int nodeId;
    private final String nodeIdentifier;

    public RaptorNode(int nodeId, String nodeIdentifier)
    {
        this.nodeId = nodeId;
        this.nodeIdentifier = requireNonNull(nodeIdentifier, "nodeIdentifier is null");
    }

    public int getNodeId()
    {
        return nodeId;
    }

    public String getNodeIdentifier()
    {
        return nodeIdentifier;
    }
}
