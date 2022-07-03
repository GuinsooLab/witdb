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
import io.trino.spi.security.TrinoPrincipal;
import io.trino.sql.tree.CreateRole;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.Identifier;

import javax.inject.Inject;

import java.util.List;
import java.util.Optional;

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static io.trino.metadata.MetadataUtil.checkRoleExists;
import static io.trino.metadata.MetadataUtil.createPrincipal;
import static io.trino.metadata.MetadataUtil.processRoleCommandCatalog;
import static io.trino.spi.StandardErrorCode.ROLE_ALREADY_EXISTS;
import static io.trino.sql.analyzer.SemanticExceptions.semanticException;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class CreateRoleTask
        implements DataDefinitionTask<CreateRole>
{
    private final Metadata metadata;
    private final AccessControl accessControl;

    @Inject
    public CreateRoleTask(Metadata metadata, AccessControl accessControl)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
    }

    @Override
    public String getName()
    {
        return "CREATE ROLE";
    }

    @Override
    public ListenableFuture<Void> execute(
            CreateRole statement,
            QueryStateMachine stateMachine,
            List<Expression> parameters,
            WarningCollector warningCollector)
    {
        Session session = stateMachine.getSession();
        Optional<String> catalog = processRoleCommandCatalog(metadata, session, statement, statement.getCatalog().map(Identifier::getValue));
        String role = statement.getName().getValue().toLowerCase(ENGLISH);
        Optional<TrinoPrincipal> grantor = statement.getGrantor().map(specification -> createPrincipal(session, specification));
        accessControl.checkCanCreateRole(session.toSecurityContext(), role, grantor, catalog);
        if (metadata.roleExists(session, role, catalog)) {
            throw semanticException(ROLE_ALREADY_EXISTS, statement, "Role '%s' already exists", role);
        }
        grantor.ifPresent(trinoPrincipal -> checkRoleExists(session, statement, metadata, trinoPrincipal, catalog));
        metadata.createRole(session, role, grantor, catalog);
        return immediateVoidFuture();
    }
}
