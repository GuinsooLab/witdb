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
package io.trino.sql.planner;

import com.google.common.collect.ImmutableList;
import io.trino.metadata.Metadata;
import io.trino.operator.join.SortedPositionLinks;
import io.trino.sql.ExpressionUtils;
import io.trino.sql.tree.AstVisitor;
import io.trino.sql.tree.BetweenPredicate;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.Node;
import io.trino.sql.tree.SymbolReference;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.sql.tree.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * Extracts sort expression to be used for creating {@link SortedPositionLinks} from join filter expression.
 * Currently this class can extract sort and search expressions from filter function conjuncts of shape:
 * <p>
 * {@code A.a < f(B.x, B.y, B.z)} or {@code f(B.x, B.y, B.z) < A.a}
 * <p>
 * where {@code a} is the build side symbol reference and {@code x,y,z} are probe
 * side symbol references. Any of inequality operators ({@code <,<=,>,>=}) can be used.
 * Same build side symbol need to be used in all conjuncts.
 */
public final class SortExpressionExtractor
{
    /* TODO:
       This class could be extended to handle any expressions like:
       A.a * sin(A.b) / log(B.x) < cos(B.z)
       by transforming it to:
       f(A.a, A.b) < g(B.x, B.z)
       Where f(...) and g(...) would be some functions/expressions. That
       would allow us to perform binary search on arbitrary complex expressions
       by sorting position links according to the result of f(...) function.
     */
    private SortExpressionExtractor() {}

    public static Optional<SortExpressionContext> extractSortExpression(Metadata metadata, Set<Symbol> buildSymbols, Expression filter)
    {
        List<Expression> filterConjuncts = ExpressionUtils.extractConjuncts(filter);
        SortExpressionVisitor visitor = new SortExpressionVisitor(buildSymbols);

        List<SortExpressionContext> sortExpressionCandidates = ImmutableList.copyOf(filterConjuncts.stream()
                .filter(expression -> DeterminismEvaluator.isDeterministic(expression, metadata))
                .map(visitor::process)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toMap(SortExpressionContext::getSortExpression, identity(), SortExpressionExtractor::merge))
                .values());

        // For now heuristically pick sort expression which has most search expressions assigned to it.
        // TODO: make it cost based decision based on symbol statistics
        return sortExpressionCandidates.stream()
                .sorted(comparing(context -> -1 * context.getSearchExpressions().size()))
                .findFirst();
    }

    private static SortExpressionContext merge(SortExpressionContext left, SortExpressionContext right)
    {
        checkArgument(left.getSortExpression().equals(right.getSortExpression()));
        ImmutableList.Builder<Expression> searchExpressions = ImmutableList.builder();
        searchExpressions.addAll(left.getSearchExpressions());
        searchExpressions.addAll(right.getSearchExpressions());
        return new SortExpressionContext(left.getSortExpression(), searchExpressions.build());
    }

    private static class SortExpressionVisitor
            extends AstVisitor<Optional<SortExpressionContext>, Void>
    {
        private final Set<Symbol> buildSymbols;

        public SortExpressionVisitor(Set<Symbol> buildSymbols)
        {
            this.buildSymbols = buildSymbols;
        }

        @Override
        protected Optional<SortExpressionContext> visitExpression(Expression expression, Void context)
        {
            return Optional.empty();
        }

        @Override
        protected Optional<SortExpressionContext> visitComparisonExpression(ComparisonExpression comparison, Void context)
        {
            return switch (comparison.getOperator()) {
                case GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL -> {
                    Optional<SymbolReference> sortChannel = asBuildSymbolReference(buildSymbols, comparison.getRight());
                    boolean hasBuildReferencesOnOtherSide = hasBuildSymbolReference(buildSymbols, comparison.getLeft());
                    if (sortChannel.isEmpty()) {
                        sortChannel = asBuildSymbolReference(buildSymbols, comparison.getLeft());
                        hasBuildReferencesOnOtherSide = hasBuildSymbolReference(buildSymbols, comparison.getRight());
                    }
                    if (sortChannel.isPresent() && !hasBuildReferencesOnOtherSide) {
                        yield sortChannel.map(symbolReference -> new SortExpressionContext(symbolReference, singletonList(comparison)));
                    }
                    yield Optional.empty();
                }
                default -> Optional.empty();
            };
        }

        @Override
        protected Optional<SortExpressionContext> visitBetweenPredicate(BetweenPredicate node, Void context)
        {
            Optional<SortExpressionContext> result = visitComparisonExpression(new ComparisonExpression(GREATER_THAN_OR_EQUAL, node.getValue(), node.getMin()), context);
            if (result.isPresent()) {
                return result;
            }
            return visitComparisonExpression(new ComparisonExpression(LESS_THAN_OR_EQUAL, node.getValue(), node.getMax()), context);
        }
    }

    private static Optional<SymbolReference> asBuildSymbolReference(Set<Symbol> buildLayout, Expression expression)
    {
        // Currently we only support symbol as sort expression on build side
        if (expression instanceof SymbolReference symbolReference) {
            if (buildLayout.contains(new Symbol(symbolReference.getName()))) {
                return Optional.of(symbolReference);
            }
        }
        return Optional.empty();
    }

    private static boolean hasBuildSymbolReference(Set<Symbol> buildSymbols, Expression expression)
    {
        return new BuildSymbolReferenceFinder(buildSymbols).process(expression);
    }

    private static class BuildSymbolReferenceFinder
            extends AstVisitor<Boolean, Void>
    {
        private final Set<String> buildSymbols;

        public BuildSymbolReferenceFinder(Set<Symbol> buildSymbols)
        {
            this.buildSymbols = buildSymbols.stream()
                    .map(Symbol::getName)
                    .collect(toImmutableSet());
        }

        @Override
        protected Boolean visitNode(Node node, Void context)
        {
            for (Node child : node.getChildren()) {
                if (process(child, context)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected Boolean visitSymbolReference(SymbolReference symbolReference, Void context)
        {
            return buildSymbols.contains(symbolReference.getName());
        }
    }
}
