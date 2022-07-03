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

import io.trino.metadata.Metadata;
import io.trino.sql.planner.DesugarAtTimeZoneRewriter;
import io.trino.sql.planner.TypeAnalyzer;

import static java.util.Objects.requireNonNull;

public class DesugarAtTimeZone
        extends ExpressionRewriteRuleSet
{
    public DesugarAtTimeZone(Metadata metadata, TypeAnalyzer typeAnalyzer)
    {
        super(createRewrite(metadata, typeAnalyzer));
    }

    private static ExpressionRewriter createRewrite(Metadata metadata, TypeAnalyzer typeAnalyzer)
    {
        requireNonNull(metadata, "metadata is null");
        requireNonNull(typeAnalyzer, "typeAnalyzer is null");

        return (expression, context) -> DesugarAtTimeZoneRewriter.rewrite(expression, context.getSession(), metadata, typeAnalyzer, context.getSymbolAllocator());
    }
}
