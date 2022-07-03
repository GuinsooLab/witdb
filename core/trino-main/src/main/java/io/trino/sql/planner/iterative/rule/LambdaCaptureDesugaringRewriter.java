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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.SymbolAllocator;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.tree.BindExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.ExpressionRewriter;
import io.trino.sql.tree.ExpressionTreeRewriter;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.LambdaArgumentDeclaration;
import io.trino.sql.tree.LambdaExpression;
import io.trino.sql.tree.SymbolReference;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.sql.planner.ExpressionSymbolInliner.inlineSymbols;
import static java.util.Objects.requireNonNull;

public final class LambdaCaptureDesugaringRewriter
{
    public static Expression rewrite(Expression expression, TypeProvider symbolTypes, SymbolAllocator symbolAllocator)
    {
        return ExpressionTreeRewriter.rewriteWith(new Visitor(symbolTypes, symbolAllocator), expression, new Context());
    }

    private LambdaCaptureDesugaringRewriter() {}

    private static class Visitor
            extends ExpressionRewriter<Context>
    {
        private final TypeProvider symbolTypes;
        private final SymbolAllocator symbolAllocator;

        public Visitor(TypeProvider symbolTypes, SymbolAllocator symbolAllocator)
        {
            this.symbolTypes = requireNonNull(symbolTypes, "symbolTypes is null");
            this.symbolAllocator = requireNonNull(symbolAllocator, "symbolAllocator is null");
        }

        @Override
        public Expression rewriteLambdaExpression(LambdaExpression node, Context context, ExpressionTreeRewriter<Context> treeRewriter)
        {
            // Use linked hash set to guarantee deterministic iteration order
            LinkedHashSet<Symbol> referencedSymbols = new LinkedHashSet<>();
            Expression rewrittenBody = treeRewriter.rewrite(node.getBody(), context.withReferencedSymbols(referencedSymbols));

            List<Symbol> lambdaArguments = node.getArguments().stream()
                    .map(LambdaArgumentDeclaration::getName)
                    .map(Identifier::getValue)
                    .map(Symbol::new)
                    .collect(toImmutableList());

            Set<Symbol> captureSymbols = Sets.difference(referencedSymbols, ImmutableSet.copyOf(lambdaArguments));

            // x -> f(x, captureSymbol)    will be rewritten into
            // "$internal$bind"(captureSymbol, (extraSymbol, x) -> f(x, extraSymbol))

            ImmutableMap.Builder<Symbol, Symbol> captureSymbolToExtraSymbol = ImmutableMap.builder();
            ImmutableList.Builder<LambdaArgumentDeclaration> newLambdaArguments = ImmutableList.builder();
            for (Symbol captureSymbol : captureSymbols) {
                Symbol extraSymbol = symbolAllocator.newSymbol(captureSymbol.getName(), symbolTypes.get(captureSymbol));
                captureSymbolToExtraSymbol.put(captureSymbol, extraSymbol);
                newLambdaArguments.add(new LambdaArgumentDeclaration(new Identifier(extraSymbol.getName())));
            }
            newLambdaArguments.addAll(node.getArguments());

            ImmutableMap<Symbol, Symbol> symbolsMap = captureSymbolToExtraSymbol.buildOrThrow();
            Function<Symbol, Expression> symbolMapping = symbol -> symbolsMap.getOrDefault(symbol, symbol).toSymbolReference();
            Expression rewrittenExpression = new LambdaExpression(newLambdaArguments.build(), inlineSymbols(symbolMapping, rewrittenBody));

            if (captureSymbols.size() != 0) {
                List<Expression> capturedValues = captureSymbols.stream()
                        .map(symbol -> new SymbolReference(symbol.getName()))
                        .collect(toImmutableList());
                rewrittenExpression = new BindExpression(capturedValues, rewrittenExpression);
            }

            context.getReferencedSymbols().addAll(captureSymbols);
            return rewrittenExpression;
        }

        @Override
        public Expression rewriteSymbolReference(SymbolReference node, Context context, ExpressionTreeRewriter<Context> treeRewriter)
        {
            context.getReferencedSymbols().add(new Symbol(node.getName()));
            return null;
        }
    }

    private static class Context
    {
        // Use linked hash set to guarantee deterministic iteration order
        final LinkedHashSet<Symbol> referencedSymbols;

        public Context()
        {
            this(new LinkedHashSet<>());
        }

        private Context(LinkedHashSet<Symbol> referencedSymbols)
        {
            this.referencedSymbols = referencedSymbols;
        }

        public LinkedHashSet<Symbol> getReferencedSymbols()
        {
            return referencedSymbols;
        }

        public Context withReferencedSymbols(LinkedHashSet<Symbol> symbols)
        {
            return new Context(symbols);
        }
    }
}
