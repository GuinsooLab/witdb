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
package io.trino.sql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.metadata.TableFunctionHandle;
import io.trino.spi.ptf.Argument;
import io.trino.spi.ptf.NameAndPosition;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.WindowNode.Specification;

import javax.annotation.concurrent.Immutable;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Immutable
public class TableFunctionNode
        extends PlanNode
{
    private final String name;
    private final Map<String, Argument> arguments;
    private final List<Symbol> properOutputs;
    private final List<PlanNode> sources;
    private final List<TableArgumentProperties> tableArgumentProperties;
    private final Map<NameAndPosition, Symbol> inputDescriptorMappings;
    private final TableFunctionHandle handle;

    @JsonCreator
    public TableFunctionNode(
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("name") String name,
            @JsonProperty("arguments") Map<String, Argument> arguments,
            @JsonProperty("properOutputs") List<Symbol> properOutputs,
            @JsonProperty("sources") List<PlanNode> sources,
            @JsonProperty("tableArgumentProperties") List<TableArgumentProperties> tableArgumentProperties,
            @JsonProperty("inputDescriptorMappings") Map<NameAndPosition, Symbol> inputDescriptorMappings,
            @JsonProperty("handle") TableFunctionHandle handle)
    {
        super(id);
        this.name = requireNonNull(name, "name is null");
        this.arguments = requireNonNull(arguments, "arguments is null");
        this.properOutputs = requireNonNull(properOutputs, "properOutputs is null");
        this.sources = requireNonNull(sources, "sources is null");
        this.tableArgumentProperties = requireNonNull(tableArgumentProperties, "tableArgumentProperties is null");
        this.inputDescriptorMappings = requireNonNull(inputDescriptorMappings, "inputDescriptorMappings is null");
        this.handle = requireNonNull(handle, "handle is null");
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @JsonProperty
    public Map<String, Argument> getArguments()
    {
        return arguments;
    }

    @JsonProperty
    public List<Symbol> getProperOutputs()
    {
        return properOutputs;
    }

    @JsonProperty
    public List<TableArgumentProperties> getTableArgumentProperties()
    {
        return tableArgumentProperties;
    }

    @JsonProperty
    public Map<NameAndPosition, Symbol> getInputDescriptorMappings()
    {
        return inputDescriptorMappings;
    }

    @JsonProperty
    public TableFunctionHandle getHandle()
    {
        return handle;
    }

    @JsonProperty
    @Override
    public List<PlanNode> getSources()
    {
        return sources;
    }

    @Override
    public List<Symbol> getOutputSymbols()
    {
        // TODO add outputs from input relations
        return properOutputs;
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitTableFunction(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newSources)
    {
        checkArgument(sources.size() == newSources.size(), "wrong number of new children");
        return new TableFunctionNode(getId(), name, arguments, properOutputs, newSources, tableArgumentProperties, inputDescriptorMappings, handle);
    }

    public static class TableArgumentProperties
    {
        private final boolean rowSemantics;
        private final boolean pruneWhenEmpty;
        private final boolean passThroughColumns;
        private final Specification specification;

        @JsonCreator
        public TableArgumentProperties(
                @JsonProperty("rowSemantics") boolean rowSemantics,
                @JsonProperty("pruneWhenEmpty") boolean pruneWhenEmpty,
                @JsonProperty("passThroughColumns") boolean passThroughColumns,
                @JsonProperty("specification") Specification specification)
        {
            this.rowSemantics = rowSemantics;
            this.pruneWhenEmpty = pruneWhenEmpty;
            this.passThroughColumns = passThroughColumns;
            this.specification = requireNonNull(specification, "specification is null");
        }

        @JsonProperty
        public boolean isRowSemantics()
        {
            return rowSemantics;
        }

        @JsonProperty
        public boolean isPruneWhenEmpty()
        {
            return pruneWhenEmpty;
        }

        @JsonProperty
        public boolean isPassThroughColumns()
        {
            return passThroughColumns;
        }

        @JsonProperty
        public Specification getSpecification()
        {
            return specification;
        }
    }
}
