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
package io.trino.sql.planner.iterative.rule;

import io.trino.matching.Capture;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.metadata.Metadata;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.plan.TableDeleteNode;
import io.trino.sql.planner.plan.TableFinishNode;
import io.trino.sql.planner.plan.TableScanNode;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.matching.Capture.newCapture;
import static io.trino.sql.planner.plan.Patterns.delete;
import static io.trino.sql.planner.plan.Patterns.source;
import static io.trino.sql.planner.plan.Patterns.tableFinish;
import static io.trino.sql.planner.plan.Patterns.tableScan;
import static java.util.Objects.requireNonNull;

public class PushDeleteIntoConnector
        implements Rule<TableFinishNode>
{
    private static final Capture<TableScanNode> TABLE_SCAN = newCapture();
    private static final Pattern<TableFinishNode> PATTERN =
            tableFinish().with(source().matching(
                    delete().with(source().matching(
                            tableScan().capturedAs(TABLE_SCAN)))));

    private final Metadata metadata;

    public PushDeleteIntoConnector(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public Pattern<TableFinishNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(TableFinishNode node, Captures captures, Context context)
    {
        TableScanNode tableScan = captures.get(TABLE_SCAN);

        return metadata.applyDelete(context.getSession(), tableScan.getTable())
                .map(newHandle -> new TableDeleteNode(
                        context.getIdAllocator().getNextId(),
                        newHandle,
                        getOnlyElement(node.getOutputSymbols())))
                .map(Result::ofPlanNode)
                .orElseGet(Result::empty);
    }
}
