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
package io.trino.execution;

import com.google.common.util.concurrent.ListenableFuture;
import io.trino.Session;
import io.trino.execution.warnings.WarningCollector;
import io.trino.metadata.Metadata;
import io.trino.security.AccessControl;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.SetSchemaAuthorization;

import javax.inject.Inject;

import java.util.List;
import java.util.Optional;

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static io.trino.metadata.MetadataUtil.checkRoleExists;
import static io.trino.metadata.MetadataUtil.createCatalogSchemaName;
import static io.trino.metadata.MetadataUtil.createPrincipal;
import static io.trino.spi.StandardErrorCode.SCHEMA_NOT_FOUND;
import static io.trino.sql.analyzer.SemanticExceptions.semanticException;
import static java.util.Objects.requireNonNull;

public class SetSchemaAuthorizationTask
        implements DataDefinitionTask<SetSchemaAuthorization>
{
    private final Metadata metadata;
    private final AccessControl accessControl;

    @Inject
    public SetSchemaAuthorizationTask(Metadata metadata, AccessControl accessControl)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
    }

    @Override
    public String getName()
    {
        return "SET SCHEMA AUTHORIZATION";
    }

    @Override
    public ListenableFuture<Void> execute(
            SetSchemaAuthorization statement,
            QueryStateMachine stateMachine,
            List<Expression> parameters,
            WarningCollector warningCollector)
    {
        Session session = stateMachine.getSession();

        CatalogSchemaName source = createCatalogSchemaName(session, statement, Optional.of(statement.getSource()));

        if (!metadata.schemaExists(session, source)) {
            throw semanticException(SCHEMA_NOT_FOUND, statement, "Schema '%s' does not exist", source);
        }
        TrinoPrincipal principal = createPrincipal(statement.getPrincipal());
        checkRoleExists(session, statement, metadata, principal, Optional.of(source.getCatalogName()).filter(catalog -> metadata.isCatalogManagedSecurity(session, catalog)));

        accessControl.checkCanSetSchemaAuthorization(session.toSecurityContext(), source, principal);

        metadata.setSchemaAuthorization(session, source, principal);

        return immediateVoidFuture();
    }
}
