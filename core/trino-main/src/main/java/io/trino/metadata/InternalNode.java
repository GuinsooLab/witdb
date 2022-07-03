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
package io.trino.metadata;

import io.trino.client.NodeVersion;
import io.trino.spi.HostAddress;
import io.trino.spi.Node;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.nullToEmpty;
import static io.airlift.node.AddressToHostname.tryDecodeHostnameToAddress;
import static java.util.Objects.requireNonNull;

/**
 * A node is a server in a cluster than can process queries.
 */
public class InternalNode
        implements Node
{
    private final String nodeIdentifier;
    private final URI internalUri;
    private final NodeVersion nodeVersion;
    private final boolean coordinator;

    public InternalNode(String nodeIdentifier, URI internalUri, NodeVersion nodeVersion, boolean coordinator)
    {
        nodeIdentifier = emptyToNull(nullToEmpty(nodeIdentifier).trim());
        this.nodeIdentifier = requireNonNull(nodeIdentifier, "nodeIdentifier is null or empty");
        this.internalUri = requireNonNull(internalUri, "internalUri is null");
        this.nodeVersion = requireNonNull(nodeVersion, "nodeVersion is null");
        this.coordinator = coordinator;
    }

    @Override
    public String getNodeIdentifier()
    {
        return nodeIdentifier;
    }

    @Override
    public String getHost()
    {
        return internalUri.getHost();
    }

    @Override
    @Deprecated
    public URI getHttpUri()
    {
        return getInternalUri();
    }

    public URI getInternalUri()
    {
        return internalUri;
    }

    public InetAddress getInternalAddress()
            throws UnknownHostException
    {
        Optional<InetAddress> address = tryDecodeHostnameToAddress(internalUri.getHost());
        if (address.isPresent()) {
            return address.get();
        }
        return InetAddress.getByName(internalUri.getHost());
    }

    @Override
    public HostAddress getHostAndPort()
    {
        return HostAddress.fromUri(internalUri);
    }

    @Override
    public String getVersion()
    {
        return nodeVersion.getVersion();
    }

    @Override
    public boolean isCoordinator()
    {
        return coordinator;
    }

    public NodeVersion getNodeVersion()
    {
        return nodeVersion;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        InternalNode o = (InternalNode) obj;
        return nodeIdentifier.equals(o.nodeIdentifier);
    }

    @Override
    public int hashCode()
    {
        return nodeIdentifier.hashCode();
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("nodeIdentifier", nodeIdentifier)
                .add("internalUri", internalUri)
                .add("nodeVersion", nodeVersion)
                .toString();
    }
}
