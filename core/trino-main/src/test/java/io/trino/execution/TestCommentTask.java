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

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.trino.execution.warnings.WarningCollector;
import io.trino.metadata.QualifiedObjectName;
import io.trino.security.AllowAllAccessControl;
import io.trino.sql.tree.Comment;
import io.trino.sql.tree.QualifiedName;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.trino.spi.StandardErrorCode.TABLE_NOT_FOUND;
import static io.trino.sql.tree.Comment.Type.TABLE;
import static io.trino.sql.tree.Comment.Type.VIEW;
import static io.trino.testing.assertions.TrinoExceptionAssert.assertTrinoExceptionThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@Test(singleThreaded = true)
public class TestCommentTask
        extends BaseDataDefinitionTaskTest
{
    // TODO: Add test for 'COMMENT ON COLUMN' statements

    @Test
    public void testCommentTable()
    {
        QualifiedObjectName tableName = qualifiedObjectName("existing_table");
        metadata.createTable(testSession, CATALOG_NAME, someTable(tableName), false);
        assertThat(metadata.getTableMetadata(testSession, metadata.getTableHandle(testSession, tableName).get()).getMetadata().getComment())
                .isEmpty();

        getFutureValue(setComment(TABLE, asQualifiedName(tableName), Optional.of("new comment")));
        assertThat(metadata.getTableMetadata(testSession, metadata.getTableHandle(testSession, tableName).get()).getMetadata().getComment())
                .isEqualTo(Optional.of("new comment"));
    }

    @Test
    public void testCommentTableOnView()
    {
        QualifiedObjectName viewName = qualifiedObjectName("existing_view");
        metadata.createView(testSession, viewName, someView(), false);

        assertTrinoExceptionThrownBy(() -> getFutureValue(setComment(TABLE, asQualifiedName(viewName), Optional.of("new comment"))))
                .hasErrorCode(TABLE_NOT_FOUND)
                .hasMessage("Table '%1$s' does not exist, but a view with that name exists. Did you mean COMMENT ON VIEW %1$s IS ...?", viewName);
    }

    @Test
    public void testCommentTableOnMaterializedView()
    {
        QualifiedObjectName materializedViewName = qualifiedObjectName("existing_materialized_view");
        metadata.createMaterializedView(testSession, QualifiedObjectName.valueOf(materializedViewName.toString()), someMaterializedView(), false, false);

        assertTrinoExceptionThrownBy(() -> getFutureValue(setComment(TABLE, asQualifiedName(materializedViewName), Optional.of("new comment"))))
                .hasErrorCode(TABLE_NOT_FOUND)
                .hasMessage("Table '%s' does not exist, but a materialized view with that name exists. Setting comments on materialized views is unsupported.", materializedViewName);
    }

    @Test
    public void testCommentView()
    {
        QualifiedObjectName viewName = qualifiedObjectName("existing_view");
        metadata.createView(testSession, viewName, someView(), false);
        assertThat(metadata.isView(testSession, viewName)).isTrue();

        getFutureValue(setComment(VIEW, asQualifiedName(viewName), Optional.of("new comment")));
        assertThat(metadata.getView(testSession, viewName).get().getComment()).isEqualTo(Optional.of("new comment"));
    }

    @Test
    public void testCommentViewOnTable()
    {
        QualifiedObjectName tableName = qualifiedObjectName("existing_table");
        metadata.createTable(testSession, CATALOG_NAME, someTable(tableName), false);

        assertTrinoExceptionThrownBy(() -> getFutureValue(setComment(VIEW, asQualifiedName(tableName), Optional.of("new comment"))))
                .hasErrorCode(TABLE_NOT_FOUND)
                .hasMessage("View '%1$s' does not exist, but a table with that name exists. Did you mean COMMENT ON TABLE %1$s IS ...?", tableName);
    }

    @Test
    public void testCommentViewOnMaterializedView()
    {
        QualifiedObjectName materializedViewName = qualifiedObjectName("existing_materialized_view");
        metadata.createMaterializedView(testSession, QualifiedObjectName.valueOf(materializedViewName.toString()), someMaterializedView(), false, false);

        assertTrinoExceptionThrownBy(() -> getFutureValue(setComment(VIEW, asQualifiedName(materializedViewName), Optional.of("new comment"))))
                .hasErrorCode(TABLE_NOT_FOUND)
                .hasMessage("View '%s' does not exist, but a materialized view with that name exists. Setting comments on materialized views is unsupported.", materializedViewName);
    }

    private ListenableFuture<Void> setComment(Comment.Type type, QualifiedName viewName, Optional<String> comment)
    {
        return new CommentTask(metadata, new AllowAllAccessControl()).execute(new Comment(type, viewName, comment), queryStateMachine, ImmutableList.of(), WarningCollector.NOOP);
    }
}
