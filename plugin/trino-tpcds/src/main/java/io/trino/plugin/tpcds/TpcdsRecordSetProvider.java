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
package io.trino.plugin.tpcds;

import com.google.common.collect.ImmutableList;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorRecordSetProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.RecordSet;
import io.trino.tpcds.Results;
import io.trino.tpcds.Session;
import io.trino.tpcds.Table;
import io.trino.tpcds.column.Column;

import java.util.List;

import static io.trino.tpcds.Results.constructResults;
import static io.trino.tpcds.Table.getTable;

public class TpcdsRecordSetProvider
        implements ConnectorRecordSetProvider
{
    @Override
    public RecordSet getRecordSet(
            ConnectorTransactionHandle transaction,
            ConnectorSession connectorSession,
            ConnectorSplit split,
            ConnectorTableHandle tableHandle,
            List<? extends ColumnHandle> columns)
    {
        TpcdsSplit tpcdsSplit = (TpcdsSplit) split;
        TpcdsTableHandle tpcdsTable = (TpcdsTableHandle) tableHandle;

        Table table = getTable(tpcdsTable.getTableName());
        double scaleFactor = tpcdsTable.getScaleFactor();
        int partNumber = tpcdsSplit.getPartNumber();
        int totalParts = tpcdsSplit.getTotalParts();
        boolean noSexism = tpcdsSplit.isNoSexism();

        ImmutableList.Builder<Column> builder = ImmutableList.builder();
        for (ColumnHandle column : columns) {
            String columnName = ((TpcdsColumnHandle) column).getColumnName();
            builder.add(table.getColumn(columnName));
        }

        Session session = Session.getDefaultSession()
                .withScale(scaleFactor)
                .withParallelism(totalParts)
                .withChunkNumber(partNumber + 1)
                .withTable(table)
                .withNoSexism(noSexism);
        Results results = constructResults(table, session);
        return new TpcdsRecordSet(results, builder.build());
    }
}
