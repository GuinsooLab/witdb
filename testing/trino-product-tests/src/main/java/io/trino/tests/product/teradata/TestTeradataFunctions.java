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
package io.trino.tests.product.teradata;

import io.trino.tempto.ProductTest;
import org.testng.annotations.Test;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tests.product.TestGroups.FUNCTIONS;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;

public class TestTeradataFunctions
        extends ProductTest
{
    @Test(groups = FUNCTIONS)
    public void testIndex()
    {
        assertThat(onTrino().executeQuery("SELECT index('high', 'ig')")).contains(row(2));
    }

    @Test(groups = FUNCTIONS)
    public void testChar2HexInt()
    {
        assertThat(onTrino().executeQuery("SELECT char2hexint('ಠ益ಠ')")).contains(row("0CA076CA0CA0"));
    }

    @Test(groups = FUNCTIONS)
    public void testToDate()
    {
        assertThat(onTrino().executeQuery("SELECT to_date('1988/04/01', 'yyyy/mm/dd')"))
                .contains(row(Date.valueOf("1988-04-01")));
        assertThat(onTrino().executeQuery("SELECT to_date('1988/04/08', 'yyyy/mm/dd')"))
                .contains(row(Date.valueOf("1988-04-08")));
    }

    @Test(groups = FUNCTIONS)
    public void testToTimestamp()
    {
        assertThat(onTrino().executeQuery("SELECT to_timestamp('1988/04/08;02:03:04','yyyy/mm/dd;hh24:mi:ss')"))
                .contains(row(Timestamp.valueOf(LocalDateTime.of(1988, 4, 8, 2, 3, 4))));
    }

    @Test(groups = FUNCTIONS)
    public void testToChar()
    {
        assertThat(onTrino().executeQuery("SELECT to_char(TIMESTAMP '1988-04-08 14:15:16 +02:09','yyyy/mm/dd;hh24:mi:ss')"))
                .contains(row("1988/04/08;14:15:16"));
    }
}
