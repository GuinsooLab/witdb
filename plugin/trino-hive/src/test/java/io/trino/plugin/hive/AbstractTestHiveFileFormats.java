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
package io.trino.plugin.hive;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.plugin.hive.metastore.StorageFormat;
import io.trino.spi.Page;
import io.trino.spi.PageBuilder;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Int128;
import io.trino.spi.type.RowType;
import io.trino.spi.type.SqlDate;
import io.trino.spi.type.SqlDecimal;
import io.trino.spi.type.SqlTimestamp;
import io.trino.spi.type.SqlVarbinary;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.type.Date;
import org.apache.hadoop.hive.common.type.HiveChar;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.common.type.HiveVarchar;
import org.apache.hadoop.hive.common.type.Timestamp;
import org.apache.hadoop.hive.ql.exec.FileSinkOperator.RecordWriter;
import org.apache.hadoop.hive.ql.io.HiveOutputFormat;
import org.apache.hadoop.hive.serde2.Serializer;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.SettableStructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.JavaHiveCharObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.JavaHiveDecimalObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.JobConf;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.trino.hadoop.ConfigurationInstantiator.newEmptyConfiguration;
import static io.trino.plugin.hive.HiveColumnHandle.ColumnType.PARTITION_KEY;
import static io.trino.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.trino.plugin.hive.HiveColumnHandle.createBaseColumn;
import static io.trino.plugin.hive.HiveColumnProjectionInfo.generatePartialName;
import static io.trino.plugin.hive.HivePartitionKey.HIVE_DEFAULT_DYNAMIC_PARTITION;
import static io.trino.plugin.hive.HiveTestUtils.SESSION;
import static io.trino.plugin.hive.HiveTestUtils.isDistinctFrom;
import static io.trino.plugin.hive.HiveTestUtils.mapType;
import static io.trino.plugin.hive.acid.AcidTransaction.NO_ACID_TRANSACTION;
import static io.trino.plugin.hive.util.CompressionConfigUtil.configureCompression;
import static io.trino.plugin.hive.util.HiveUtil.isStructuralType;
import static io.trino.plugin.hive.util.SerDeUtils.serializeObject;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.Chars.padSpaces;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.DateTimeTestingUtils.sqlTimestampOf;
import static io.trino.testing.MaterializedResult.materializeSourceDataStream;
import static io.trino.testing.StructuralTestUtil.arrayBlockOf;
import static io.trino.testing.StructuralTestUtil.decimalArrayBlockOf;
import static io.trino.testing.StructuralTestUtil.decimalMapBlockOf;
import static io.trino.testing.StructuralTestUtil.mapBlockOf;
import static io.trino.testing.StructuralTestUtil.rowBlockOf;
import static io.trino.type.DateTimes.MICROSECONDS_PER_MILLISECOND;
import static io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.floorDiv;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.fill;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardListObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardMapObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardStructObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaBooleanObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteArrayObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDateObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDoubleObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaFloatObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaHiveVarcharObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaIntObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaLongObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaShortObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaStringObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaTimestampObjectInspector;
import static org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory.getCharTypeInfo;
import static org.joda.time.DateTimeZone.UTC;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(groups = "hive")
public abstract class AbstractTestHiveFileFormats
{
    protected static final DateTimeZone HIVE_STORAGE_TIME_ZONE = DateTimeZone.forID("America/Bahia_Banderas");

    private static final double EPSILON = 0.001;

    private static final long DATE_MILLIS_UTC = new DateTime(2011, 5, 6, 0, 0, UTC).getMillis();
    private static final long DATE_DAYS = TimeUnit.MILLISECONDS.toDays(DATE_MILLIS_UTC);
    private static final String DATE_STRING = DateTimeFormat.forPattern("yyyy-MM-dd").withZoneUTC().print(DATE_MILLIS_UTC);
    private static final Date HIVE_DATE = Date.ofEpochMilli(DATE_MILLIS_UTC);

    private static final DateTime TIMESTAMP = new DateTime(2011, 5, 6, 7, 8, 9, 123, UTC);
    private static final long TIMESTAMP_MICROS = TIMESTAMP.getMillis() * MICROSECONDS_PER_MILLISECOND;
    private static final String TIMESTAMP_STRING = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS").withZoneUTC().print(TIMESTAMP.getMillis());
    private static final Timestamp HIVE_TIMESTAMP = Timestamp.ofEpochMilli(TIMESTAMP.getMillis());

    private static final String VARCHAR_MAX_LENGTH_STRING;

    static {
        char[] varcharMaxLengthCharArray = new char[HiveVarchar.MAX_VARCHAR_LENGTH];
        fill(varcharMaxLengthCharArray, 'a');
        VARCHAR_MAX_LENGTH_STRING = new String(varcharMaxLengthCharArray);
    }

    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_2 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(2, 1));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_4 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(4, 2));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_8 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(8, 4));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_17 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(17, 8));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_18 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(18, 8));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_38 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(38, 16));

    private static final DecimalType DECIMAL_TYPE_PRECISION_2 = DecimalType.createDecimalType(2, 1);
    private static final DecimalType DECIMAL_TYPE_PRECISION_4 = DecimalType.createDecimalType(4, 2);
    private static final DecimalType DECIMAL_TYPE_PRECISION_8 = DecimalType.createDecimalType(8, 4);
    private static final DecimalType DECIMAL_TYPE_PRECISION_17 = DecimalType.createDecimalType(17, 8);
    private static final DecimalType DECIMAL_TYPE_PRECISION_18 = DecimalType.createDecimalType(18, 8);
    private static final DecimalType DECIMAL_TYPE_PRECISION_38 = DecimalType.createDecimalType(38, 16);

    private static final HiveDecimal WRITE_DECIMAL_PRECISION_2 = HiveDecimal.create(new BigDecimal("-1.2"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_4 = HiveDecimal.create(new BigDecimal("12.3"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_8 = HiveDecimal.create(new BigDecimal("-1234.5678"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_17 = HiveDecimal.create(new BigDecimal("123456789.1234"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_18 = HiveDecimal.create(new BigDecimal("-1234567890.12345678"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_38 = HiveDecimal.create(new BigDecimal("1234567890123456789012.12345678"));

    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_2 = new BigDecimal("-1.2");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_4 = new BigDecimal("12.30");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_8 = new BigDecimal("-1234.5678");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_17 = new BigDecimal("123456789.12340000");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_18 = new BigDecimal("-1234567890.12345678");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_38 = new BigDecimal("1234567890123456789012.1234567800000000");

    private static final JavaHiveCharObjectInspector CHAR_INSPECTOR_LENGTH_10 =
            new JavaHiveCharObjectInspector(getCharTypeInfo(10));

    // TODO: support null values and determine if timestamp and binary are allowed as partition keys
    public static final List<TestColumn> TEST_COLUMNS = ImmutableList.<TestColumn>builder()
            .add(new TestColumn("p_empty_string", javaStringObjectInspector, "", Slices.EMPTY_SLICE, true))
            .add(new TestColumn("p_string", javaStringObjectInspector, "test", Slices.utf8Slice("test"), true))
            .add(new TestColumn("p_empty_varchar", javaHiveVarcharObjectInspector, "", Slices.EMPTY_SLICE, true))
            .add(new TestColumn("p_varchar", javaHiveVarcharObjectInspector, "test", Slices.utf8Slice("test"), true))
            .add(new TestColumn("p_varchar_max_length", javaHiveVarcharObjectInspector, VARCHAR_MAX_LENGTH_STRING, Slices.utf8Slice(VARCHAR_MAX_LENGTH_STRING), true))
            .add(new TestColumn("p_char_10", CHAR_INSPECTOR_LENGTH_10, "test", Slices.utf8Slice("test"), true))
            .add(new TestColumn("p_tinyint", javaByteObjectInspector, "1", (byte) 1, true))
            .add(new TestColumn("p_smallint", javaShortObjectInspector, "2", (short) 2, true))
            .add(new TestColumn("p_int", javaIntObjectInspector, "3", 3, true))
            .add(new TestColumn("p_bigint", javaLongObjectInspector, "4", 4L, true))
            .add(new TestColumn("p_float", javaFloatObjectInspector, "5.1", 5.1f, true))
            .add(new TestColumn("p_double", javaDoubleObjectInspector, "6.2", 6.2, true))
            .add(new TestColumn("p_boolean", javaBooleanObjectInspector, "true", true, true))
            .add(new TestColumn("p_date", javaDateObjectInspector, DATE_STRING, DATE_DAYS, true))
            .add(new TestColumn("p_timestamp", javaTimestampObjectInspector, TIMESTAMP_STRING, TIMESTAMP_MICROS, true))
            .add(new TestColumn("p_decimal_precision_2", DECIMAL_INSPECTOR_PRECISION_2, WRITE_DECIMAL_PRECISION_2.toString(), EXPECTED_DECIMAL_PRECISION_2, true))
            .add(new TestColumn("p_decimal_precision_4", DECIMAL_INSPECTOR_PRECISION_4, WRITE_DECIMAL_PRECISION_4.toString(), EXPECTED_DECIMAL_PRECISION_4, true))
            .add(new TestColumn("p_decimal_precision_8", DECIMAL_INSPECTOR_PRECISION_8, WRITE_DECIMAL_PRECISION_8.toString(), EXPECTED_DECIMAL_PRECISION_8, true))
            .add(new TestColumn("p_decimal_precision_17", DECIMAL_INSPECTOR_PRECISION_17, WRITE_DECIMAL_PRECISION_17.toString(), EXPECTED_DECIMAL_PRECISION_17, true))
            .add(new TestColumn("p_decimal_precision_18", DECIMAL_INSPECTOR_PRECISION_18, WRITE_DECIMAL_PRECISION_18.toString(), EXPECTED_DECIMAL_PRECISION_18, true))
            .add(new TestColumn("p_decimal_precision_38", DECIMAL_INSPECTOR_PRECISION_38, WRITE_DECIMAL_PRECISION_38.toString() + "BD", EXPECTED_DECIMAL_PRECISION_38, true))
//            .add(new TestColumn("p_binary", javaByteArrayObjectInspector, "test2", Slices.utf8Slice("test2"), true))
            .add(new TestColumn("p_null_string", javaStringObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_varchar", javaHiveVarcharObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_char", CHAR_INSPECTOR_LENGTH_10, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_tinyint", javaByteObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_smallint", javaShortObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_int", javaIntObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_bigint", javaLongObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_float", javaFloatObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_double", javaDoubleObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_boolean", javaBooleanObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_date", javaDateObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_timestamp", javaTimestampObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_precision_2", DECIMAL_INSPECTOR_PRECISION_2, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_precision_4", DECIMAL_INSPECTOR_PRECISION_4, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_precision_8", DECIMAL_INSPECTOR_PRECISION_8, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_precision_17", DECIMAL_INSPECTOR_PRECISION_17, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_precision_18", DECIMAL_INSPECTOR_PRECISION_18, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_precision_38", DECIMAL_INSPECTOR_PRECISION_38, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))

//            .add(new TestColumn("p_null_binary", javaByteArrayObjectInspector, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("t_null_string", javaStringObjectInspector, null, null))
            .add(new TestColumn("t_null_varchar", javaHiveVarcharObjectInspector, null, null))
            .add(new TestColumn("t_null_char", CHAR_INSPECTOR_LENGTH_10, null, null))
            .add(new TestColumn("t_null_array_int", getStandardListObjectInspector(javaIntObjectInspector), null, null))
            .add(new TestColumn("t_null_decimal_precision_2", DECIMAL_INSPECTOR_PRECISION_2, null, null))
            .add(new TestColumn("t_null_decimal_precision_4", DECIMAL_INSPECTOR_PRECISION_4, null, null))
            .add(new TestColumn("t_null_decimal_precision_8", DECIMAL_INSPECTOR_PRECISION_8, null, null))
            .add(new TestColumn("t_null_decimal_precision_17", DECIMAL_INSPECTOR_PRECISION_17, null, null))
            .add(new TestColumn("t_null_decimal_precision_18", DECIMAL_INSPECTOR_PRECISION_18, null, null))
            .add(new TestColumn("t_null_decimal_precision_38", DECIMAL_INSPECTOR_PRECISION_38, null, null))
            .add(new TestColumn("t_empty_string", javaStringObjectInspector, "", Slices.EMPTY_SLICE))
            .add(new TestColumn("t_string", javaStringObjectInspector, "test", Slices.utf8Slice("test")))
            .add(new TestColumn("t_empty_varchar", javaHiveVarcharObjectInspector, new HiveVarchar("", HiveVarchar.MAX_VARCHAR_LENGTH), Slices.EMPTY_SLICE))
            .add(new TestColumn("t_varchar", javaHiveVarcharObjectInspector, new HiveVarchar("test", HiveVarchar.MAX_VARCHAR_LENGTH), Slices.utf8Slice("test")))
            .add(new TestColumn("t_varchar_max_length", javaHiveVarcharObjectInspector, new HiveVarchar(VARCHAR_MAX_LENGTH_STRING, HiveVarchar.MAX_VARCHAR_LENGTH), Slices.utf8Slice(VARCHAR_MAX_LENGTH_STRING)))
            .add(new TestColumn("t_char", CHAR_INSPECTOR_LENGTH_10, "test", Slices.utf8Slice("test"), true))
            .add(new TestColumn("t_tinyint", javaByteObjectInspector, (byte) 1, (byte) 1))
            .add(new TestColumn("t_smallint", javaShortObjectInspector, (short) 2, (short) 2))
            .add(new TestColumn("t_int", javaIntObjectInspector, 3, 3))
            .add(new TestColumn("t_bigint", javaLongObjectInspector, 4L, 4L))
            .add(new TestColumn("t_float", javaFloatObjectInspector, 5.1f, 5.1f))
            .add(new TestColumn("t_double", javaDoubleObjectInspector, 6.2, 6.2))
            .add(new TestColumn("t_boolean_true", javaBooleanObjectInspector, true, true))
            .add(new TestColumn("t_boolean_false", javaBooleanObjectInspector, false, false))
            .add(new TestColumn("t_date", javaDateObjectInspector, HIVE_DATE, DATE_DAYS))
            .add(new TestColumn("t_timestamp", javaTimestampObjectInspector, HIVE_TIMESTAMP, TIMESTAMP_MICROS))
            .add(new TestColumn("t_decimal_precision_2", DECIMAL_INSPECTOR_PRECISION_2, WRITE_DECIMAL_PRECISION_2, EXPECTED_DECIMAL_PRECISION_2))
            .add(new TestColumn("t_decimal_precision_4", DECIMAL_INSPECTOR_PRECISION_4, WRITE_DECIMAL_PRECISION_4, EXPECTED_DECIMAL_PRECISION_4))
            .add(new TestColumn("t_decimal_precision_8", DECIMAL_INSPECTOR_PRECISION_8, WRITE_DECIMAL_PRECISION_8, EXPECTED_DECIMAL_PRECISION_8))
            .add(new TestColumn("t_decimal_precision_17", DECIMAL_INSPECTOR_PRECISION_17, WRITE_DECIMAL_PRECISION_17, EXPECTED_DECIMAL_PRECISION_17))
            .add(new TestColumn("t_decimal_precision_18", DECIMAL_INSPECTOR_PRECISION_18, WRITE_DECIMAL_PRECISION_18, EXPECTED_DECIMAL_PRECISION_18))
            .add(new TestColumn("t_decimal_precision_38", DECIMAL_INSPECTOR_PRECISION_38, WRITE_DECIMAL_PRECISION_38, EXPECTED_DECIMAL_PRECISION_38))
            .add(new TestColumn("t_binary", javaByteArrayObjectInspector, Slices.utf8Slice("test2").getBytes(), Slices.utf8Slice("test2")))
            .add(new TestColumn("t_map_string",
                    getStandardMapObjectInspector(javaStringObjectInspector, javaStringObjectInspector),
                    ImmutableMap.of("test", "test"),
                    mapBlockOf(createUnboundedVarcharType(), createUnboundedVarcharType(), "test", "test")))
            .add(new TestColumn("t_map_tinyint",
                    getStandardMapObjectInspector(javaByteObjectInspector, javaByteObjectInspector),
                    ImmutableMap.of((byte) 1, (byte) 1),
                    mapBlockOf(TINYINT, TINYINT, (byte) 1, (byte) 1)))
            .add(new TestColumn("t_map_varchar",
                    getStandardMapObjectInspector(javaHiveVarcharObjectInspector, javaHiveVarcharObjectInspector),
                    ImmutableMap.of(new HiveVarchar("test", HiveVarchar.MAX_VARCHAR_LENGTH), new HiveVarchar("test", HiveVarchar.MAX_VARCHAR_LENGTH)),
                    mapBlockOf(createVarcharType(HiveVarchar.MAX_VARCHAR_LENGTH), createVarcharType(HiveVarchar.MAX_VARCHAR_LENGTH), "test", "test")))
            .add(new TestColumn("t_map_char",
                    getStandardMapObjectInspector(CHAR_INSPECTOR_LENGTH_10, CHAR_INSPECTOR_LENGTH_10),
                    ImmutableMap.of(new HiveChar("test", 10), new HiveChar("test", 10)),
                    mapBlockOf(createCharType(10), createCharType(10), "test", "test")))
            .add(new TestColumn("t_map_smallint",
                    getStandardMapObjectInspector(javaShortObjectInspector, javaShortObjectInspector),
                    ImmutableMap.of((short) 2, (short) 2),
                    mapBlockOf(SMALLINT, SMALLINT, (short) 2, (short) 2)))
            .add(new TestColumn("t_map_null_key",
                    getStandardMapObjectInspector(javaLongObjectInspector, javaLongObjectInspector),
                    asMap(new Long[] {null, 2L}, new Long[] {0L, 3L}),
                    mapBlockOf(BIGINT, BIGINT, 2, 3)))
            .add(new TestColumn("t_map_int",
                    getStandardMapObjectInspector(javaIntObjectInspector, javaIntObjectInspector),
                    ImmutableMap.of(3, 3),
                    mapBlockOf(INTEGER, INTEGER, 3, 3)))
            .add(new TestColumn("t_map_bigint",
                    getStandardMapObjectInspector(javaLongObjectInspector, javaLongObjectInspector),
                    ImmutableMap.of(4L, 4L),
                    mapBlockOf(BIGINT, BIGINT, 4L, 4L)))
            .add(new TestColumn("t_map_float",
                    getStandardMapObjectInspector(javaFloatObjectInspector, javaFloatObjectInspector),
                    ImmutableMap.of(5.0f, 5.0f), mapBlockOf(REAL, REAL, 5.0f, 5.0f)))
            .add(new TestColumn("t_map_double",
                    getStandardMapObjectInspector(javaDoubleObjectInspector, javaDoubleObjectInspector),
                    ImmutableMap.of(6.0, 6.0), mapBlockOf(DOUBLE, DOUBLE, 6.0, 6.0)))
            .add(new TestColumn("t_map_boolean",
                    getStandardMapObjectInspector(javaBooleanObjectInspector, javaBooleanObjectInspector),
                    ImmutableMap.of(true, true),
                    mapBlockOf(BOOLEAN, BOOLEAN, true, true)))
            .add(new TestColumn("t_map_date",
                    getStandardMapObjectInspector(javaDateObjectInspector, javaDateObjectInspector),
                    ImmutableMap.of(HIVE_DATE, HIVE_DATE),
                    mapBlockOf(DateType.DATE, DateType.DATE, DATE_DAYS, DATE_DAYS)))
            .add(new TestColumn("t_map_timestamp",
                    getStandardMapObjectInspector(javaTimestampObjectInspector, javaTimestampObjectInspector),
                    ImmutableMap.of(HIVE_TIMESTAMP, HIVE_TIMESTAMP),
                    mapBlockOf(TimestampType.TIMESTAMP_MILLIS, TimestampType.TIMESTAMP_MILLIS, TIMESTAMP_MICROS, TIMESTAMP_MICROS)))
            .add(new TestColumn("t_map_decimal_precision_2",
                    getStandardMapObjectInspector(DECIMAL_INSPECTOR_PRECISION_2, DECIMAL_INSPECTOR_PRECISION_2),
                    ImmutableMap.of(WRITE_DECIMAL_PRECISION_2, WRITE_DECIMAL_PRECISION_2),
                    decimalMapBlockOf(DECIMAL_TYPE_PRECISION_2, EXPECTED_DECIMAL_PRECISION_2)))
            .add(new TestColumn("t_map_decimal_precision_4",
                    getStandardMapObjectInspector(DECIMAL_INSPECTOR_PRECISION_4, DECIMAL_INSPECTOR_PRECISION_4),
                    ImmutableMap.of(WRITE_DECIMAL_PRECISION_4, WRITE_DECIMAL_PRECISION_4),
                    decimalMapBlockOf(DECIMAL_TYPE_PRECISION_4, EXPECTED_DECIMAL_PRECISION_4)))
            .add(new TestColumn("t_map_decimal_precision_8",
                    getStandardMapObjectInspector(DECIMAL_INSPECTOR_PRECISION_8, DECIMAL_INSPECTOR_PRECISION_8),
                    ImmutableMap.of(WRITE_DECIMAL_PRECISION_8, WRITE_DECIMAL_PRECISION_8),
                    decimalMapBlockOf(DECIMAL_TYPE_PRECISION_8, EXPECTED_DECIMAL_PRECISION_8)))
            .add(new TestColumn("t_map_decimal_precision_17",
                    getStandardMapObjectInspector(DECIMAL_INSPECTOR_PRECISION_17, DECIMAL_INSPECTOR_PRECISION_17),
                    ImmutableMap.of(WRITE_DECIMAL_PRECISION_17, WRITE_DECIMAL_PRECISION_17),
                    decimalMapBlockOf(DECIMAL_TYPE_PRECISION_17, EXPECTED_DECIMAL_PRECISION_17)))
            .add(new TestColumn("t_map_decimal_precision_18",
                    getStandardMapObjectInspector(DECIMAL_INSPECTOR_PRECISION_18, DECIMAL_INSPECTOR_PRECISION_18),
                    ImmutableMap.of(WRITE_DECIMAL_PRECISION_18, WRITE_DECIMAL_PRECISION_18),
                    decimalMapBlockOf(DECIMAL_TYPE_PRECISION_18, EXPECTED_DECIMAL_PRECISION_18)))
            .add(new TestColumn("t_map_decimal_precision_38",
                    getStandardMapObjectInspector(DECIMAL_INSPECTOR_PRECISION_38, DECIMAL_INSPECTOR_PRECISION_38),
                    ImmutableMap.of(WRITE_DECIMAL_PRECISION_38, WRITE_DECIMAL_PRECISION_38),
                    decimalMapBlockOf(DECIMAL_TYPE_PRECISION_38, EXPECTED_DECIMAL_PRECISION_38)))
            .add(new TestColumn("t_array_empty", getStandardListObjectInspector(javaStringObjectInspector), ImmutableList.of(), arrayBlockOf(createUnboundedVarcharType())))
            .add(new TestColumn("t_array_string", getStandardListObjectInspector(javaStringObjectInspector), ImmutableList.of("test"), arrayBlockOf(createUnboundedVarcharType(), "test")))
            .add(new TestColumn("t_array_tinyint", getStandardListObjectInspector(javaByteObjectInspector), ImmutableList.of((byte) 1), arrayBlockOf(TINYINT, (byte) 1)))
            .add(new TestColumn("t_array_smallint", getStandardListObjectInspector(javaShortObjectInspector), ImmutableList.of((short) 2), arrayBlockOf(SMALLINT, (short) 2)))
            .add(new TestColumn("t_array_int", getStandardListObjectInspector(javaIntObjectInspector), ImmutableList.of(3), arrayBlockOf(INTEGER, 3)))
            .add(new TestColumn("t_array_bigint", getStandardListObjectInspector(javaLongObjectInspector), ImmutableList.of(4L), arrayBlockOf(BIGINT, 4L)))
            .add(new TestColumn("t_array_float", getStandardListObjectInspector(javaFloatObjectInspector), ImmutableList.of(5.0f), arrayBlockOf(REAL, 5.0f)))
            .add(new TestColumn("t_array_double", getStandardListObjectInspector(javaDoubleObjectInspector), ImmutableList.of(6.0), arrayBlockOf(DOUBLE, 6.0)))
            .add(new TestColumn("t_array_boolean", getStandardListObjectInspector(javaBooleanObjectInspector), ImmutableList.of(true), arrayBlockOf(BOOLEAN, true)))
            .add(new TestColumn(
                    "t_array_varchar",
                    getStandardListObjectInspector(javaHiveVarcharObjectInspector),
                    ImmutableList.of(new HiveVarchar("test", HiveVarchar.MAX_VARCHAR_LENGTH)),
                    arrayBlockOf(createVarcharType(HiveVarchar.MAX_VARCHAR_LENGTH), "test")))
            .add(new TestColumn(
                    "t_array_char",
                    getStandardListObjectInspector(CHAR_INSPECTOR_LENGTH_10),
                    ImmutableList.of(new HiveChar("test", 10)),
                    arrayBlockOf(createCharType(10), "test")))
            .add(new TestColumn("t_array_date",
                    getStandardListObjectInspector(javaDateObjectInspector),
                    ImmutableList.of(HIVE_DATE),
                    arrayBlockOf(DateType.DATE, DATE_DAYS)))
            .add(new TestColumn("t_array_timestamp",
                    getStandardListObjectInspector(javaTimestampObjectInspector),
                    ImmutableList.of(HIVE_TIMESTAMP),
                    arrayBlockOf(TimestampType.TIMESTAMP_MILLIS, TIMESTAMP_MICROS)))
            .add(new TestColumn("t_array_decimal_precision_2",
                    getStandardListObjectInspector(DECIMAL_INSPECTOR_PRECISION_2),
                    ImmutableList.of(WRITE_DECIMAL_PRECISION_2),
                    decimalArrayBlockOf(DECIMAL_TYPE_PRECISION_2, EXPECTED_DECIMAL_PRECISION_2)))
            .add(new TestColumn("t_array_decimal_precision_4",
                    getStandardListObjectInspector(DECIMAL_INSPECTOR_PRECISION_4),
                    ImmutableList.of(WRITE_DECIMAL_PRECISION_4),
                    decimalArrayBlockOf(DECIMAL_TYPE_PRECISION_4, EXPECTED_DECIMAL_PRECISION_4)))
            .add(new TestColumn("t_array_decimal_precision_8",
                    getStandardListObjectInspector(DECIMAL_INSPECTOR_PRECISION_8),
                    ImmutableList.of(WRITE_DECIMAL_PRECISION_8),
                    decimalArrayBlockOf(DECIMAL_TYPE_PRECISION_8, EXPECTED_DECIMAL_PRECISION_8)))
            .add(new TestColumn("t_array_decimal_precision_17",
                    getStandardListObjectInspector(DECIMAL_INSPECTOR_PRECISION_17),
                    ImmutableList.of(WRITE_DECIMAL_PRECISION_17),
                    decimalArrayBlockOf(DECIMAL_TYPE_PRECISION_17, EXPECTED_DECIMAL_PRECISION_17)))
            .add(new TestColumn("t_array_decimal_precision_18",
                    getStandardListObjectInspector(DECIMAL_INSPECTOR_PRECISION_18),
                    ImmutableList.of(WRITE_DECIMAL_PRECISION_18),
                    decimalArrayBlockOf(DECIMAL_TYPE_PRECISION_18, EXPECTED_DECIMAL_PRECISION_18)))
            .add(new TestColumn("t_array_decimal_precision_38",
                    getStandardListObjectInspector(DECIMAL_INSPECTOR_PRECISION_38),
                    ImmutableList.of(WRITE_DECIMAL_PRECISION_38),
                    decimalArrayBlockOf(DECIMAL_TYPE_PRECISION_38, EXPECTED_DECIMAL_PRECISION_38)))
            .add(new TestColumn("t_struct_bigint",
                    getStandardStructObjectInspector(ImmutableList.of("s_bigint"), ImmutableList.of(javaLongObjectInspector)),
                    new Long[] {1L},
                    rowBlockOf(ImmutableList.of(BIGINT), 1)))
            .add(new TestColumn("t_complex",
                    getStandardMapObjectInspector(
                            javaStringObjectInspector,
                            getStandardListObjectInspector(
                                    getStandardStructObjectInspector(
                                            ImmutableList.of("s_int"),
                                            ImmutableList.of(javaIntObjectInspector)))),
                    ImmutableMap.of("test", ImmutableList.<Object>of(new Integer[] {1})),
                    mapBlockOf(createUnboundedVarcharType(), new ArrayType(RowType.anonymous(ImmutableList.of(INTEGER))),
                            "test", arrayBlockOf(RowType.anonymous(ImmutableList.of(INTEGER)), rowBlockOf(ImmutableList.of(INTEGER), 1L)))))
            .add(new TestColumn("t_map_null_key_complex_value",
                    getStandardMapObjectInspector(
                            javaStringObjectInspector,
                            getStandardMapObjectInspector(javaLongObjectInspector, javaBooleanObjectInspector)),
                    asMap(new String[] {null, "k"}, new ImmutableMap[] {ImmutableMap.of(15L, true), ImmutableMap.of(16L, false)}),
                    mapBlockOf(createUnboundedVarcharType(), mapType(BIGINT, BOOLEAN), "k", mapBlockOf(BIGINT, BOOLEAN, 16L, false))))
            .add(new TestColumn("t_map_null_key_complex_key_value",
                    getStandardMapObjectInspector(
                            getStandardListObjectInspector(javaStringObjectInspector),
                            getStandardMapObjectInspector(javaLongObjectInspector, javaBooleanObjectInspector)),
                    asMap(new ImmutableList[] {null, ImmutableList.of("k", "ka")}, new ImmutableMap[] {ImmutableMap.of(15L, true), ImmutableMap.of(16L, false)}),
                    mapBlockOf(new ArrayType(createUnboundedVarcharType()), mapType(BIGINT, BOOLEAN), arrayBlockOf(createUnboundedVarcharType(), "k", "ka"), mapBlockOf(BIGINT, BOOLEAN, 16L, false))))
            .add(new TestColumn("t_struct_nested", getStandardStructObjectInspector(ImmutableList.of("struct_field"),
                    ImmutableList.of(getStandardListObjectInspector(javaStringObjectInspector))), ImmutableList.of(ImmutableList.of("1", "2", "3")), rowBlockOf(ImmutableList.of(new ArrayType(createUnboundedVarcharType())), arrayBlockOf(createUnboundedVarcharType(), "1", "2", "3"))))
            .add(new TestColumn("t_struct_null", getStandardStructObjectInspector(ImmutableList.of("struct_field_null", "struct_field_null2"),
                    ImmutableList.of(javaStringObjectInspector, javaStringObjectInspector)), Arrays.asList(null, null), rowBlockOf(ImmutableList.of(createUnboundedVarcharType(), createUnboundedVarcharType()), null, null)))
            .add(new TestColumn("t_struct_non_nulls_after_nulls", getStandardStructObjectInspector(ImmutableList.of("struct_non_nulls_after_nulls1", "struct_non_nulls_after_nulls2"),
                    ImmutableList.of(javaIntObjectInspector, javaStringObjectInspector)), Arrays.asList(null, "some string"), rowBlockOf(ImmutableList.of(INTEGER, createUnboundedVarcharType()), null, "some string")))
            .add(new TestColumn("t_nested_struct_non_nulls_after_nulls",
                    getStandardStructObjectInspector(
                            ImmutableList.of("struct_field1", "struct_field2", "strict_field3"),
                            ImmutableList.of(
                                    javaIntObjectInspector,
                                    javaStringObjectInspector,
                                    getStandardStructObjectInspector(
                                            ImmutableList.of("nested_struct_field1", "nested_struct_field2"),
                                            ImmutableList.of(javaIntObjectInspector, javaStringObjectInspector)))),
                    Arrays.asList(null, "some string", Arrays.asList(null, "nested_string2")),
                    rowBlockOf(
                            ImmutableList.of(
                                    INTEGER,
                                    createUnboundedVarcharType(),
                                    RowType.anonymous(ImmutableList.of(INTEGER, createUnboundedVarcharType()))),
                            null, "some string", rowBlockOf(ImmutableList.of(INTEGER, createUnboundedVarcharType()), null, "nested_string2"))))
            .add(new TestColumn("t_map_null_value",
                    getStandardMapObjectInspector(javaStringObjectInspector, javaStringObjectInspector),
                    asMap(new String[] {"k1", "k2", "k3"}, new String[] {"v1", null, "v3"}),
                    mapBlockOf(createUnboundedVarcharType(), createUnboundedVarcharType(), new String[] {"k1", "k2", "k3"}, new String[] {"v1", null, "v3"})))
            .add(new TestColumn("t_array_string_starting_with_nulls", getStandardListObjectInspector(javaStringObjectInspector), Arrays.asList(null, "test"), arrayBlockOf(createUnboundedVarcharType(), null, "test")))
            .add(new TestColumn("t_array_string_with_nulls_in_between", getStandardListObjectInspector(javaStringObjectInspector), Arrays.asList("test-1", null, "test-2"), arrayBlockOf(createUnboundedVarcharType(), "test-1", null, "test-2")))
            .add(new TestColumn("t_array_string_ending_with_nulls", getStandardListObjectInspector(javaStringObjectInspector), Arrays.asList("test", null), arrayBlockOf(createUnboundedVarcharType(), "test", null)))
            .add(new TestColumn("t_array_string_all_nulls", getStandardListObjectInspector(javaStringObjectInspector), Arrays.asList(null, null, null), arrayBlockOf(createUnboundedVarcharType(), null, null, null)))
            .build();

    private static <K, V> Map<K, V> asMap(K[] keys, V[] values)
    {
        checkArgument(keys.length == values.length, "array lengths don't match");
        Map<K, V> map = new HashMap<>();
        int len = keys.length;
        for (int i = 0; i < len; i++) {
            map.put(keys[i], values[i]);
        }
        return map;
    }

    protected List<HiveColumnHandle> getColumnHandles(List<TestColumn> testColumns)
    {
        List<HiveColumnHandle> columns = new ArrayList<>();
        Map<String, Integer> hiveColumnIndexes = new HashMap<>();

        int nextHiveColumnIndex = 0;
        for (int i = 0; i < testColumns.size(); i++) {
            TestColumn testColumn = testColumns.get(i);

            int columnIndex;
            if (testColumn.isPartitionKey()) {
                columnIndex = -1;
            }
            else {
                if (hiveColumnIndexes.get(testColumn.getBaseName()) != null) {
                    columnIndex = hiveColumnIndexes.get(testColumn.getBaseName());
                }
                else {
                    columnIndex = nextHiveColumnIndex++;
                    hiveColumnIndexes.put(testColumn.getBaseName(), columnIndex);
                }
            }

            if (testColumn.getDereferenceNames().size() == 0) {
                HiveType hiveType = HiveType.valueOf(testColumn.getObjectInspector().getTypeName());
                columns.add(createBaseColumn(testColumn.getName(), columnIndex, hiveType, hiveType.getType(TESTING_TYPE_MANAGER), testColumn.isPartitionKey() ? PARTITION_KEY : REGULAR, Optional.empty()));
            }
            else {
                HiveType baseHiveType = HiveType.valueOf(testColumn.getBaseObjectInspector().getTypeName());
                HiveType partialHiveType = baseHiveType.getHiveTypeForDereferences(testColumn.getDereferenceIndices()).get();
                HiveColumnHandle hiveColumnHandle = new HiveColumnHandle(
                        testColumn.getBaseName(),
                        columnIndex,
                        baseHiveType,
                        baseHiveType.getType(TESTING_TYPE_MANAGER),
                        Optional.of(new HiveColumnProjectionInfo(
                                testColumn.getDereferenceIndices(),
                                testColumn.getDereferenceNames(),
                                partialHiveType,
                                partialHiveType.getType(TESTING_TYPE_MANAGER))),
                        testColumn.isPartitionKey() ? PARTITION_KEY : REGULAR,
                        Optional.empty());
                columns.add(hiveColumnHandle);
            }
        }
        return columns;
    }

    public static FileSplit createTestFileTrino(
            String filePath,
            HiveStorageFormat storageFormat,
            HiveCompressionCodec compressionCodec,
            List<TestColumn> testColumns,
            ConnectorSession session,
            int numRows,
            HiveFileWriterFactory fileWriterFactory)
    {
        // filter out partition keys, which are not written to the file
        testColumns = testColumns.stream()
                .filter(column -> !column.isPartitionKey())
                .collect(toImmutableList());

        List<Type> types = testColumns.stream()
                .map(TestColumn::getType)
                .map(HiveType::valueOf)
                .map(type -> type.getType(TESTING_TYPE_MANAGER))
                .collect(toList());

        PageBuilder pageBuilder = new PageBuilder(types);

        for (int rowNumber = 0; rowNumber < numRows; rowNumber++) {
            pageBuilder.declarePosition();
            for (int columnNumber = 0; columnNumber < testColumns.size(); columnNumber++) {
                serializeObject(
                        types.get(columnNumber),
                        pageBuilder.getBlockBuilder(columnNumber),
                        testColumns.get(columnNumber).getWriteValue(),
                        testColumns.get(columnNumber).getObjectInspector(),
                        false);
            }
        }
        Page page = pageBuilder.build();

        JobConf jobConf = new JobConf(newEmptyConfiguration());
        configureCompression(jobConf, compressionCodec);

        Properties tableProperties = new Properties();
        tableProperties.setProperty(
                "columns",
                testColumns.stream()
                        .map(TestColumn::getName)
                        .collect(Collectors.joining(",")));

        tableProperties.setProperty(
                "columns.types",
                testColumns.stream()
                        .map(TestColumn::getType)
                        .collect(Collectors.joining(",")));

        Optional<FileWriter> fileWriter = fileWriterFactory.createFileWriter(
                new Path(filePath),
                testColumns.stream()
                        .map(TestColumn::getName)
                        .collect(toList()),
                StorageFormat.fromHiveStorageFormat(storageFormat),
                tableProperties,
                jobConf,
                session,
                OptionalInt.empty(),
                NO_ACID_TRANSACTION,
                false,
                WriterKind.INSERT);

        FileWriter hiveFileWriter = fileWriter.orElseThrow(() -> new IllegalArgumentException("fileWriterFactory"));
        hiveFileWriter.appendRows(page);
        hiveFileWriter.commit();

        return new FileSplit(new Path(filePath), 0, new File(filePath).length(), new String[0]);
    }

    public static FileSplit createTestFileHive(
            String filePath,
            HiveStorageFormat storageFormat,
            HiveCompressionCodec compressionCodec,
            List<TestColumn> testColumns,
            int numRows)
            throws Exception
    {
        HiveOutputFormat<?, ?> outputFormat = newInstance(storageFormat.getOutputFormat(), HiveOutputFormat.class);
        Serializer serializer = newInstance(storageFormat.getSerde(), Serializer.class);

        // filter out partition keys, which are not written to the file
        testColumns = testColumns.stream()
                .filter(column -> !column.isPartitionKey())
                .collect(toImmutableList());

        Properties tableProperties = new Properties();
        tableProperties.setProperty(
                "columns",
                testColumns.stream()
                        .map(TestColumn::getName)
                        .collect(Collectors.joining(",")));
        tableProperties.setProperty(
                "columns.types",
                testColumns.stream()
                        .map(TestColumn::getType)
                        .collect(Collectors.joining(",")));
        serializer.initialize(newEmptyConfiguration(), tableProperties);

        JobConf jobConf = new JobConf(newEmptyConfiguration());
        configureCompression(jobConf, compressionCodec);

        RecordWriter recordWriter = outputFormat.getHiveRecordWriter(
                jobConf,
                new Path(filePath),
                Text.class,
                compressionCodec != HiveCompressionCodec.NONE,
                tableProperties,
                () -> {});

        try {
            serializer.initialize(newEmptyConfiguration(), tableProperties);

            SettableStructObjectInspector objectInspector = getStandardStructObjectInspector(
                    testColumns.stream()
                            .map(TestColumn::getName)
                            .collect(toImmutableList()),
                    testColumns.stream()
                            .map(TestColumn::getObjectInspector)
                            .collect(toImmutableList()));

            Object row = objectInspector.create();

            List<StructField> fields = ImmutableList.copyOf(objectInspector.getAllStructFieldRefs());

            for (int rowNumber = 0; rowNumber < numRows; rowNumber++) {
                for (int i = 0; i < testColumns.size(); i++) {
                    Object writeValue = testColumns.get(i).getWriteValue();
                    if (writeValue instanceof Slice) {
                        writeValue = ((Slice) writeValue).getBytes();
                    }
                    objectInspector.setStructFieldData(row, fields.get(i), writeValue);
                }

                Writable record = serializer.serialize(row, objectInspector);
                recordWriter.write(record);
            }
        }
        finally {
            recordWriter.close(false);
        }

        // todo to test with compression, the file must be renamed with the compression extension
        Path path = new Path(filePath);
        path.getFileSystem(newEmptyConfiguration()).setVerifyChecksum(true);
        File file = new File(filePath);
        return new FileSplit(path, 0, file.length(), new String[0]);
    }

    private static <T> T newInstance(String className, Class<T> superType)
            throws ReflectiveOperationException
    {
        return HiveStorageFormat.class.getClassLoader().loadClass(className).asSubclass(superType).getConstructor().newInstance();
    }

    public static Object getFieldFromCursor(RecordCursor cursor, Type type, int field)
    {
        if (cursor.isNull(field)) {
            return null;
        }
        if (BOOLEAN.equals(type)) {
            return cursor.getBoolean(field);
        }
        if (TINYINT.equals(type)) {
            return cursor.getLong(field);
        }
        if (SMALLINT.equals(type)) {
            return cursor.getLong(field);
        }
        if (INTEGER.equals(type)) {
            return (int) cursor.getLong(field);
        }
        if (BIGINT.equals(type)) {
            return cursor.getLong(field);
        }
        if (REAL.equals(type)) {
            return intBitsToFloat((int) cursor.getLong(field));
        }
        if (DOUBLE.equals(type)) {
            return cursor.getDouble(field);
        }
        if (type instanceof VarcharType || type instanceof CharType || VARBINARY.equals(type)) {
            return cursor.getSlice(field);
        }
        if (DateType.DATE.equals(type)) {
            return cursor.getLong(field);
        }
        if (TimestampType.TIMESTAMP_MILLIS.equals(type)) {
            return cursor.getLong(field);
        }
        if (isStructuralType(type)) {
            return cursor.getObject(field);
        }
        if (type instanceof DecimalType decimalType) {
            if (decimalType.isShort()) {
                return BigInteger.valueOf(cursor.getLong(field));
            }
            return ((Int128) cursor.getObject(field)).toBigInteger();
        }
        throw new RuntimeException("unknown type");
    }

    protected void checkCursor(RecordCursor cursor, List<TestColumn> testColumns, int rowCount)
    {
        List<Type> types = testColumns.stream()
                .map(column -> column.getObjectInspector().getTypeName())
                .map(type -> HiveType.valueOf(type).getType(TESTING_TYPE_MANAGER))
                .collect(toImmutableList());

        Map<Type, MethodHandle> distinctFromOperators = types.stream().distinct()
                .collect(toImmutableMap(identity(), HiveTestUtils::distinctFromOperator));

        for (int row = 0; row < rowCount; row++) {
            assertTrue(cursor.advanceNextPosition());
            for (int i = 0, testColumnsSize = testColumns.size(); i < testColumnsSize; i++) {
                TestColumn testColumn = testColumns.get(i);

                Type type = types.get(i);
                Object fieldFromCursor = getFieldFromCursor(cursor, type, i);
                if (fieldFromCursor == null) {
                    assertEquals(null, testColumn.getExpectedValue(), "Expected null for column " + testColumn.getName());
                }
                else if (type instanceof DecimalType decimalType) {
                    fieldFromCursor = new BigDecimal((BigInteger) fieldFromCursor, decimalType.getScale());
                    assertEquals(fieldFromCursor, testColumn.getExpectedValue(), "Wrong value for column " + testColumn.getName());
                }
                else if (testColumn.getObjectInspector().getTypeName().equals("float")) {
                    assertEquals((float) fieldFromCursor, (float) testColumn.getExpectedValue(), (float) EPSILON);
                }
                else if (testColumn.getObjectInspector().getTypeName().equals("double")) {
                    assertEquals((double) fieldFromCursor, (double) testColumn.getExpectedValue(), EPSILON);
                }
                else if (testColumn.getObjectInspector().getTypeName().equals("tinyint")) {
                    assertEquals(((Number) fieldFromCursor).byteValue(), testColumn.getExpectedValue());
                }
                else if (testColumn.getObjectInspector().getTypeName().equals("smallint")) {
                    assertEquals(((Number) fieldFromCursor).shortValue(), testColumn.getExpectedValue());
                }
                else if (testColumn.getObjectInspector().getTypeName().equals("int")) {
                    assertEquals(((Number) fieldFromCursor).intValue(), testColumn.getExpectedValue());
                }
                else if (testColumn.getObjectInspector().getCategory() == Category.PRIMITIVE) {
                    assertEquals(fieldFromCursor, testColumn.getExpectedValue(), "Wrong value for column " + testColumn.getName());
                }
                else {
                    Block expected = (Block) testColumn.getExpectedValue();
                    Block actual = (Block) fieldFromCursor;
                    boolean distinct = isDistinctFrom(distinctFromOperators.get(type), expected, actual);
                    assertFalse(distinct, "Wrong value for column: " + testColumn.getName());
                }
            }
        }
        assertFalse(cursor.advanceNextPosition());
    }

    protected void checkPageSource(ConnectorPageSource pageSource, List<TestColumn> testColumns, List<Type> types, int rowCount)
            throws IOException
    {
        try {
            MaterializedResult result = materializeSourceDataStream(SESSION, pageSource, types);
            assertEquals(result.getMaterializedRows().size(), rowCount);
            for (MaterializedRow row : result) {
                for (int i = 0, testColumnsSize = testColumns.size(); i < testColumnsSize; i++) {
                    TestColumn testColumn = testColumns.get(i);
                    Type type = types.get(i);

                    Object actualValue = row.getField(i);
                    Object expectedValue = testColumn.getExpectedValue();

                    if (expectedValue instanceof Slice) {
                        expectedValue = ((Slice) expectedValue).toStringUtf8();
                    }

                    if (actualValue == null || expectedValue == null) {
                        assertEquals(actualValue, expectedValue, "Wrong value for column " + testColumn.getName());
                    }
                    else if (testColumn.getObjectInspector().getTypeName().equals("float")) {
                        assertEquals((float) actualValue, (float) expectedValue, EPSILON, "Wrong value for column " + testColumn.getName());
                    }
                    else if (testColumn.getObjectInspector().getTypeName().equals("double")) {
                        assertEquals((double) actualValue, (double) expectedValue, EPSILON, "Wrong value for column " + testColumn.getName());
                    }
                    else if (testColumn.getObjectInspector().getTypeName().equals("date")) {
                        SqlDate expectedDate = new SqlDate(((Long) expectedValue).intValue());
                        assertEquals(actualValue, expectedDate, "Wrong value for column " + testColumn.getName());
                    }
                    else if (testColumn.getObjectInspector().getTypeName().equals("int") ||
                            testColumn.getObjectInspector().getTypeName().equals("smallint") ||
                            testColumn.getObjectInspector().getTypeName().equals("tinyint")) {
                        assertEquals(actualValue, expectedValue);
                    }
                    else if (testColumn.getObjectInspector().getTypeName().equals("timestamp")) {
                        SqlTimestamp expectedTimestamp = sqlTimestampOf(floorDiv((Long) expectedValue, MICROSECONDS_PER_MILLISECOND));
                        assertEquals(actualValue, expectedTimestamp, "Wrong value for column " + testColumn.getName());
                    }
                    else if (testColumn.getObjectInspector().getTypeName().startsWith("char")) {
                        assertEquals(actualValue, padSpaces((String) expectedValue, (CharType) type), "Wrong value for column " + testColumn.getName());
                    }
                    else if (testColumn.getObjectInspector().getCategory() == Category.PRIMITIVE) {
                        if (expectedValue instanceof Slice) {
                            expectedValue = ((Slice) expectedValue).toStringUtf8();
                        }

                        if (actualValue instanceof Slice) {
                            actualValue = ((Slice) actualValue).toStringUtf8();
                        }
                        if (actualValue instanceof SqlVarbinary) {
                            actualValue = new String(((SqlVarbinary) actualValue).getBytes(), UTF_8);
                        }

                        if (actualValue instanceof SqlDecimal) {
                            actualValue = new BigDecimal(actualValue.toString());
                        }
                        assertEquals(actualValue, expectedValue, "Wrong value for column " + testColumn.getName());
                    }
                    else {
                        BlockBuilder builder = type.createBlockBuilder(null, 1);
                        type.writeObject(builder, expectedValue);
                        expectedValue = type.getObjectValue(SESSION, builder.build(), 0);
                        assertEquals(actualValue, expectedValue, "Wrong value for column " + testColumn.getName());
                    }
                }
            }
        }
        finally {
            pageSource.close();
        }
    }

    public static final class TestColumn
    {
        private final String baseName;
        private final ObjectInspector baseObjectInspector;
        private final List<String> dereferenceNames;
        private final List<Integer> dereferenceIndices;
        private final String name;
        private final ObjectInspector objectInspector;
        private final Object writeValue;
        private final Object expectedValue;
        private final boolean partitionKey;

        public TestColumn(String name, ObjectInspector objectInspector, Object writeValue, Object expectedValue)
        {
            this(name, objectInspector, writeValue, expectedValue, false);
        }

        public TestColumn(String name, ObjectInspector objectInspector, Object writeValue, Object expectedValue, boolean partitionKey)
        {
            this(name, objectInspector, ImmutableList.of(), ImmutableList.of(), objectInspector, writeValue, expectedValue, partitionKey);
        }

        public TestColumn(
                String baseName,
                ObjectInspector baseObjectInspector,
                List<String> dereferenceNames,
                List<Integer> dereferenceIndices,
                ObjectInspector objectInspector,
                Object writeValue,
                Object expectedValue,
                boolean partitionKey)
        {
            this.baseName = requireNonNull(baseName, "baseName is null");
            this.baseObjectInspector = requireNonNull(baseObjectInspector, "baseObjectInspector is null");
            this.dereferenceNames = requireNonNull(dereferenceNames, "dereferenceNames is null");
            this.dereferenceIndices = requireNonNull(dereferenceIndices, "dereferenceIndices is null");
            checkArgument(dereferenceIndices.size() == dereferenceNames.size(), "dereferenceIndices and dereferenceNames should have the same size");
            this.name = baseName + generatePartialName(dereferenceNames);
            this.objectInspector = requireNonNull(objectInspector, "objectInspector is null");
            this.writeValue = writeValue;
            this.expectedValue = expectedValue;
            this.partitionKey = partitionKey;
            checkArgument(dereferenceNames.size() == 0 || partitionKey == false, "partial column cannot be a partition key");
        }

        public String getName()
        {
            return name;
        }

        public String getBaseName()
        {
            return baseName;
        }

        public List<String> getDereferenceNames()
        {
            return dereferenceNames;
        }

        public List<Integer> getDereferenceIndices()
        {
            return dereferenceIndices;
        }

        public String getType()
        {
            return objectInspector.getTypeName();
        }

        public ObjectInspector getBaseObjectInspector()
        {
            return baseObjectInspector;
        }

        public ObjectInspector getObjectInspector()
        {
            return objectInspector;
        }

        public Object getWriteValue()
        {
            return writeValue;
        }

        public Object getExpectedValue()
        {
            return expectedValue;
        }

        public boolean isPartitionKey()
        {
            return partitionKey;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder("TestColumn{");
            sb.append("baseName='").append(baseName).append("'");
            sb.append("dereferenceNames=").append("[").append(dereferenceNames.stream().collect(Collectors.joining(","))).append("]");
            sb.append("name=").append(name);
            sb.append(", objectInspector=").append(objectInspector);
            sb.append(", writeValue=").append(writeValue);
            sb.append(", expectedValue=").append(expectedValue);
            sb.append(", partitionKey=").append(partitionKey);
            sb.append('}');
            return sb.toString();
        }
    }
}
