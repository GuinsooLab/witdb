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

import javax.inject.Inject;

import static io.trino.hdfs.authentication.UserGroupInformationUtils.executeActionInDoAs;
import static java.util.Objects.requireNonNull;

public class DirectHdfsAuthentication
        implements HdfsAuthentication
{
    private final HadoopAuthentication hadoopAuthentication;

    @Inject
    public DirectHdfsAuthentication(@ForHdfs HadoopAuthentication hadoopAuthentication)
    {
        this.hadoopAuthentication = requireNonNull(hadoopAuthentication);
    }

    @Override
    public <R, E extends Exception> R doAs(ConnectorIdentity identity, GenericExceptionAction<R, E> action)
            throws E
    {
        return executeActionInDoAs(hadoopAuthentication.getUserGroupInformation(), action);
    }
}
