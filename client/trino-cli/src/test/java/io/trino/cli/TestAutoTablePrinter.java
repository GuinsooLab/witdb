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
package io.trino.cli;

import com.google.common.collect.ImmutableList;
import io.trino.client.ClientTypeSignature;
import io.trino.client.Column;
import org.testng.annotations.Test;

import java.io.StringWriter;
import java.util.List;

import static io.trino.client.ClientStandardTypes.BIGINT;
import static io.trino.client.ClientStandardTypes.VARCHAR;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;

public class TestAutoTablePrinter
{
    @Test
    public void testNarrowPrinting()
            throws Exception
    {
        List<Column> columns = ImmutableList.<Column>builder()
                .add(column("first", VARCHAR))
                .add(column("last", VARCHAR))
                .add(column("quantity", BIGINT))
                .build();
        StringWriter writer = new StringWriter();
        OutputPrinter printer = new AutoTablePrinter(columns, writer, 100);

        printer.printRows(rows(
                row("hello", "world", 123),
                row("a", null, 4.5),
                row("b", null, null),
                row("bye", "done", -15)),
                true);
        printer.finish();

        String expected = "" +
                " first | last  | quantity \n" +
                "-------+-------+----------\n" +
                " hello | world |      123 \n" +
                " a     | NULL  |      4.5 \n" +
                " b     | NULL  |     NULL \n" +
                " bye   | done  |      -15 \n" +
                "(4 rows)\n";

        assertEquals(writer.getBuffer().toString(), expected);
    }

    @Test
    public void testWidePrinting()
            throws Exception
    {
        StringWriter writer = new StringWriter();
        List<Column> columns = ImmutableList.<Column>builder()
                .add(column("first", VARCHAR))
                .add(column("last", VARCHAR))
                .add(column("quantity", BIGINT))
                .build();
        OutputPrinter printer = new AutoTablePrinter(columns, writer, 10);

        printer.printRows(rows(
                        row("hello", "world", 123),
                        row("a", null, 4.5),
                        row("bye", "done", -15)),
                true);
        printer.finish();

        String expected = "" +
                "-[ RECORD 1 ]---\n" +
                "first    | hello\n" +
                "last     | world\n" +
                "quantity | 123\n" +
                "-[ RECORD 2 ]---\n" +
                "first    | a\n" +
                "last     | NULL\n" +
                "quantity | 4.5\n" +
                "-[ RECORD 3 ]---\n" +
                "first    | bye\n" +
                "last     | done\n" +
                "quantity | -15\n";

        assertEquals(writer.getBuffer().toString(), expected);
    }

    static Column column(String name, String type)
    {
        return new Column(name, type, new ClientTypeSignature(type));
    }

    static List<?> row(Object... values)
    {
        return asList(values);
    }

    static List<List<?>> rows(List<?>... rows)
    {
        return asList(rows);
    }
}
