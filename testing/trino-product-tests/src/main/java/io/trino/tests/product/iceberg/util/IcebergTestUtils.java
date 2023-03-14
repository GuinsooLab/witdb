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
package io.trino.tests.product.iceberg.util;

import java.net.URI;

import static io.trino.tests.product.utils.QueryExecutors.onTrino;

public final class IcebergTestUtils
{
    private IcebergTestUtils() {}

    public static String getTableLocation(String tableName)
    {
        return (String) onTrino().executeQuery("SELECT DISTINCT regexp_replace(\"$path\", '/[^/]*/[^/]*$', '') FROM " + tableName).getOnlyValue();
    }

    public static String stripNamenodeURI(String location)
    {
        return URI.create(location).getPath();
    }
}
