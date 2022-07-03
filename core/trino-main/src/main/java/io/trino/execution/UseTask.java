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
import io.trino.spi.TrinoException;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.Use;

import javax.inject.Inject;

import java.util.List;

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static io.trino.spi.StandardErrorCode.MISSING_CATALOG_NAME;
import static io.trino.spi.StandardErrorCode.NOT_FOUND;
import static io.trino.sql.analyzer.SemanticExceptions.semanticException;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class UseTask
        implements DataDefinitionTask<Use>
{
    private final Metadata metadata;

    @Inject
    public UseTask(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public String getName()
    {
        return "USE";
    }

    @Override
    public ListenableFuture<Void> execute(
            Use statement,
            QueryStateMachine stateMachine,
            List<Expression> parameters,
            WarningCollector warningCollector)
    {
        Session session = stateMachine.getSession();

        String catalog = statement.getCatalog()
                .map(identifier -> identifier.getValue().toLowerCase(ENGLISH))
                .orElseGet(() -> session.getCatalog().orElseThrow(() ->
                        semanticException(MISSING_CATALOG_NAME, statement, "Catalog must be specified when session catalog is not set")));

        if (metadata.getCatalogHandle(session, catalog).isEmpty()) {
            throw new TrinoException(NOT_FOUND, "Catalog does not exist: " + catalog);
        }

        String schema = statement.getSchema().getValue().toLowerCase(ENGLISH);

        CatalogSchemaName name = new CatalogSchemaName(catalog, schema);
        if (!metadata.schemaExists(session, name)) {
            throw new TrinoException(NOT_FOUND, "Schema does not exist: " + name);
        }

        if (statement.getCatalog().isPresent()) {
            stateMachine.setSetCatalog(catalog);
        }
        stateMachine.setSetSchema(schema);

        return immediateVoidFuture();
    }
}
