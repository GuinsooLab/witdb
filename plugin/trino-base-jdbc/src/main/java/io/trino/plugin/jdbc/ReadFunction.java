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
package io.trino.plugin.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface ReadFunction
{
    Class<?> getJavaType();

    default boolean isNull(ResultSet resultSet, int columnIndex)
            throws SQLException
    {
        // JDBC is kind of dumb: we need to read the field and then ask
        // if it was null, which means we are wasting effort here.
        // We could save the result of the field access if it matters.
        resultSet.getObject(columnIndex);
        return resultSet.wasNull();
    }

    // This should be considered to have a method as below (it doesn't to avoid autoboxing)
    //    T read(ResultSet resultSet, int columnIndex)
    //            throws SQLException;
}
