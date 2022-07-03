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
package io.trino.plugin.hive.metastore;

import com.google.common.collect.ImmutableSet;
import io.trino.spi.security.PrincipalType;
import org.testng.annotations.Test;

import static io.trino.plugin.hive.metastore.HivePrivilegeInfo.HivePrivilege.SELECT;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class TestPrincipalPrivileges
{
    @Test
    public void testGetTablePrincipalPrivileges()
    {
        PrincipalPrivileges principalPrivileges = PrincipalPrivileges.fromHivePrivilegeInfos(ImmutableSet.of(
                hivePrivilegeInfo(PrincipalType.USER, "user001"),
                hivePrivilegeInfo(PrincipalType.USER, "user002"),
                hivePrivilegeInfo(PrincipalType.ROLE, "role001")));

        assertNotNull(principalPrivileges);
        assertEquals(principalPrivileges.getUserPrivileges().size(), 2);
        assertEquals(principalPrivileges.getRolePrivileges().size(), 1);
    }

    private static HivePrivilegeInfo hivePrivilegeInfo(PrincipalType type, String key)
    {
        return new HivePrivilegeInfo(SELECT, false, new HivePrincipal(type, key), new HivePrincipal(type, key));
    }
}
