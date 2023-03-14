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
package io.trino.hdfs.authentication;

import io.trino.spi.security.ConnectorIdentity;

public interface HdfsAuthentication
{
    <R, E extends Exception> R doAs(ConnectorIdentity identity, GenericExceptionAction<R, E> action)
            throws E;

    default void doAs(ConnectorIdentity identity, Runnable action)
    {
        doAs(identity, () -> {
            action.run();
            return null;
        });
    }
}
