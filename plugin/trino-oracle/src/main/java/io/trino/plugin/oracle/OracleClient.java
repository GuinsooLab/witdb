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
package io.trino.plugin.oracle;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.plugin.base.aggregation.AggregateFunctionRewriter;
import io.trino.plugin.base.aggregation.AggregateFunctionRule;
import io.trino.plugin.base.expression.ConnectorExpressionRewriter;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.BooleanWriteFunction;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.DoubleWriteFunction;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcExpression;
import io.trino.plugin.jdbc.JdbcJoinCondition;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.LongReadFunction;
import io.trino.plugin.jdbc.LongWriteFunction;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.SliceWriteFunction;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.aggregation.ImplementAvgDecimal;
import io.trino.plugin.jdbc.aggregation.ImplementAvgFloatingPoint;
import io.trino.plugin.jdbc.aggregation.ImplementCount;
import io.trino.plugin.jdbc.aggregation.ImplementCountAll;
import io.trino.plugin.jdbc.aggregation.ImplementCountDistinct;
import io.trino.plugin.jdbc.aggregation.ImplementCovariancePop;
import io.trino.plugin.jdbc.aggregation.ImplementCovarianceSamp;
import io.trino.plugin.jdbc.aggregation.ImplementMinMax;
import io.trino.plugin.jdbc.aggregation.ImplementStddevPop;
import io.trino.plugin.jdbc.aggregation.ImplementStddevSamp;
import io.trino.plugin.jdbc.aggregation.ImplementSum;
import io.trino.plugin.jdbc.aggregation.ImplementVariancePop;
import io.trino.plugin.jdbc.aggregation.ImplementVarianceSamp;
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.plugin.jdbc.mapping.IdentifierMapping;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.JoinCondition;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleTypes;

import javax.inject.Inject;

import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static com.google.common.base.Verify.verify;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN;
import static io.trino.plugin.jdbc.PredicatePushdownController.FULL_PUSHDOWN;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.charReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.longDecimalReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.longDecimalWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.shortDecimalReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.shortDecimalWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharWriteFunction;
import static io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.getUnsupportedTypeHandling;
import static io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR;
import static io.trino.plugin.oracle.OracleSessionProperties.getNumberDefaultScale;
import static io.trino.plugin.oracle.OracleSessionProperties.getNumberRoundingMode;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.trino.spi.type.DateTimeEncoding.unpackZoneKey;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.TimestampType.TIMESTAMP_SECONDS;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_SECOND;
import static io.trino.spi.type.Timestamps.NANOSECONDS_PER_MICROSECOND;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.floorDiv;
import static java.lang.Math.floorMod;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.concurrent.TimeUnit.DAYS;

public class OracleClient
        extends BaseJdbcClient
{
    public static final int ORACLE_MAX_LIST_EXPRESSIONS = 1000;

    private static final int MAX_BYTES_PER_CHAR = 4;

    private static final int ORACLE_VARCHAR2_MAX_BYTES = 4000;
    private static final int ORACLE_VARCHAR2_MAX_CHARS = ORACLE_VARCHAR2_MAX_BYTES / MAX_BYTES_PER_CHAR;

    private static final int ORACLE_CHAR_MAX_BYTES = 2000;
    private static final int ORACLE_CHAR_MAX_CHARS = ORACLE_CHAR_MAX_BYTES / MAX_BYTES_PER_CHAR;

    private static final int PRECISION_OF_UNSPECIFIED_NUMBER = 127;

    private static final int TRINO_BIGINT_TYPE = 832_424_001;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter TIMESTAMP_SECONDS_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIMESTAMP_MILLIS_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

    private static final Set<String> INTERNAL_SCHEMAS = ImmutableSet.<String>builder()
            .add("ctxsys")
            .add("flows_files")
            .add("mdsys")
            .add("outln")
            .add("sys")
            .add("system")
            .add("xdb")
            .add("xs$null")
            .build();

    /**
     * Note the type mappings from trino -> oracle types can cause surprises since they are not invertible
     * For example, creating an oracle table in trino with a bigint column will generate an oracle table with a number column
     * Then querying the oracle table with the number column will return a decimal (not a bigint)
     */
    private static final Map<Type, WriteMapping> WRITE_MAPPINGS = ImmutableMap.<Type, WriteMapping>builder()
            .put(BOOLEAN, oracleBooleanWriteMapping())
            .put(BIGINT, WriteMapping.longMapping("number(19)", bigintWriteFunction()))
            .put(INTEGER, WriteMapping.longMapping("number(10)", integerWriteFunction()))
            .put(SMALLINT, WriteMapping.longMapping("number(5)", smallintWriteFunction()))
            .put(TINYINT, WriteMapping.longMapping("number(3)", tinyintWriteFunction()))
            .put(DOUBLE, WriteMapping.doubleMapping("binary_double", oracleDoubleWriteFunction()))
            .put(REAL, WriteMapping.longMapping("binary_float", oracleRealWriteFunction()))
            .put(VARBINARY, WriteMapping.sliceMapping("blob", varbinaryWriteFunction()))
            .put(DATE, WriteMapping.longMapping("date", trinoDateToOracleDateWriteFunction()))
            .put(TIMESTAMP_TZ_MILLIS, WriteMapping.longMapping("timestamp(3) with time zone", oracleTimestampWithTimeZoneWriteFunction()))
            .buildOrThrow();

    private final boolean synonymsEnabled;
    private final ConnectorExpressionRewriter<String> connectorExpressionRewriter;
    private final AggregateFunctionRewriter<JdbcExpression, String> aggregateFunctionRewriter;

    @Inject
    public OracleClient(
            BaseJdbcConfig config,
            OracleConfig oracleConfig,
            ConnectionFactory connectionFactory,
            QueryBuilder queryBuilder,
            IdentifierMapping identifierMapping,
            RemoteQueryModifier queryModifier)
    {
        super(config, "\"", connectionFactory, queryBuilder, identifierMapping, queryModifier);

        this.synonymsEnabled = oracleConfig.isSynonymsEnabled();

        this.connectorExpressionRewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
                .addStandardRules(this::quoted)
                .build();

        JdbcTypeHandle bigintTypeHandle = new JdbcTypeHandle(TRINO_BIGINT_TYPE, Optional.of("NUMBER"), Optional.of(0), Optional.of(0), Optional.empty(), Optional.empty());
        this.aggregateFunctionRewriter = new AggregateFunctionRewriter<>(
                connectorExpressionRewriter,
                ImmutableSet.<AggregateFunctionRule<JdbcExpression, String>>builder()
                        .add(new ImplementCountAll(bigintTypeHandle))
                        .add(new ImplementCount(bigintTypeHandle))
                        .add(new ImplementCountDistinct(bigintTypeHandle, true))
                        .add(new ImplementMinMax(true))
                        .add(new ImplementSum(OracleClient::toTypeHandle))
                        .add(new ImplementAvgFloatingPoint())
                        .add(new ImplementAvgDecimal())
                        .add(new ImplementStddevSamp())
                        .add(new ImplementStddevPop())
                        .add(new ImplementVarianceSamp())
                        .add(new ImplementVariancePop())
                        .add(new ImplementCovarianceSamp())
                        .add(new ImplementCovariancePop())
                        .build());
    }

    @Override
    protected Optional<List<String>> getTableTypes()
    {
        if (synonymsEnabled) {
            return Optional.of(ImmutableList.of("TABLE", "VIEW", "SYNONYM"));
        }
        return Optional.of(ImmutableList.of("TABLE", "VIEW"));
    }

    @Override
    protected boolean filterSchema(String schemaName)
    {
        if (INTERNAL_SCHEMAS.contains(schemaName.toLowerCase(ENGLISH))) {
            return false;
        }
        return super.filterSchema(schemaName);
    }

    @Override
    public PreparedStatement getPreparedStatement(Connection connection, String sql)
            throws SQLException
    {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setFetchSize(1000);
        return statement;
    }

    @Override
    protected void renameTable(ConnectorSession session, Connection connection, String catalogName, String remoteSchemaName, String remoteTableName, String newRemoteSchemaName, String newRemoteTableName)
            throws SQLException
    {
        if (!remoteSchemaName.equals(newRemoteSchemaName)) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming tables across schemas");
        }

        String newTableName = newRemoteTableName.toUpperCase(ENGLISH);
        execute(session, connection, format(
                "ALTER TABLE %s RENAME TO %s",
                quoted(catalogName, remoteSchemaName, remoteTableName),
                quoted(newTableName)));
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName)
    {
        // ORA-02420: missing schema authorization clause
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating schemas");
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping schemas");
    }

    @Override
    public void renameSchema(ConnectorSession session, String schemaName, String newSchemaName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming schemas");
    }

    @Override
    public Optional<String> getTableComment(ResultSet resultSet)
    {
        // Don't return a comment until the connector supports creating tables with comment
        return Optional.empty();
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        if (typeHandle.getJdbcType() == TRINO_BIGINT_TYPE) {
            // Synthetic column
            return Optional.of(bigintColumnMapping());
        }

        String jdbcTypeName = typeHandle.getJdbcTypeName()
                .orElseThrow(() -> new TrinoException(JDBC_ERROR, "Type name is missing: " + typeHandle));

        Optional<ColumnMapping> mappingToVarchar = getForcedMappingToVarchar(typeHandle);
        if (mappingToVarchar.isPresent()) {
            return mappingToVarchar;
        }

        if (jdbcTypeName.equalsIgnoreCase("date")) {
            return Optional.of(ColumnMapping.longMapping(
                    TIMESTAMP_SECONDS,
                    oracleTimestampReadFunction(),
                    trinoTimestampToOracleDateWriteFunction(),
                    FULL_PUSHDOWN));
        }

        switch (typeHandle.getJdbcType()) {
            case Types.SMALLINT:
                return Optional.of(ColumnMapping.longMapping(
                        SMALLINT,
                        ResultSet::getShort,
                        smallintWriteFunction(),
                        FULL_PUSHDOWN));
            case OracleTypes.BINARY_FLOAT:
                return Optional.of(ColumnMapping.longMapping(
                        REAL,
                        (resultSet, columnIndex) -> floatToRawIntBits(resultSet.getFloat(columnIndex)),
                        oracleRealWriteFunction(),
                        FULL_PUSHDOWN));

            case OracleTypes.BINARY_DOUBLE:
            case OracleTypes.FLOAT:
                return Optional.of(ColumnMapping.doubleMapping(
                        DOUBLE,
                        ResultSet::getDouble,
                        oracleDoubleWriteFunction(),
                        FULL_PUSHDOWN));
            case OracleTypes.NUMBER:
                int actualPrecision = typeHandle.getRequiredColumnSize();
                int decimalDigits = typeHandle.getRequiredDecimalDigits();
                // Map negative scale to decimal(p+s, 0).
                int precision = actualPrecision + max(-decimalDigits, 0);
                int scale = max(decimalDigits, 0);
                Optional<Integer> numberDefaultScale = getNumberDefaultScale(session);
                RoundingMode roundingMode = getNumberRoundingMode(session);
                if (precision < scale) {
                    if (roundingMode == RoundingMode.UNNECESSARY) {
                        break;
                    }
                    scale = min(Decimals.MAX_PRECISION, scale);
                    precision = scale;
                }
                else if (numberDefaultScale.isPresent() && precision == PRECISION_OF_UNSPECIFIED_NUMBER) {
                    precision = Decimals.MAX_PRECISION;
                    scale = numberDefaultScale.get();
                }
                else if (precision > Decimals.MAX_PRECISION || actualPrecision <= 0) {
                    break;
                }
                DecimalType decimalType = createDecimalType(precision, scale);
                // JDBC driver can return BigDecimal with lower scale than column's scale when there are trailing zeroes
                if (decimalType.isShort()) {
                    return Optional.of(ColumnMapping.longMapping(
                            decimalType,
                            shortDecimalReadFunction(decimalType, roundingMode),
                            shortDecimalWriteFunction(decimalType),
                            FULL_PUSHDOWN));
                }
                return Optional.of(ColumnMapping.objectMapping(
                        decimalType,
                        longDecimalReadFunction(decimalType, roundingMode),
                        longDecimalWriteFunction(decimalType),
                        FULL_PUSHDOWN));

            case OracleTypes.CHAR:
            case OracleTypes.NCHAR:
                CharType charType = createCharType(typeHandle.getRequiredColumnSize());
                return Optional.of(ColumnMapping.sliceMapping(
                        charType,
                        charReadFunction(charType),
                        oracleCharWriteFunction(),
                        FULL_PUSHDOWN));

            case OracleTypes.VARCHAR:
            case OracleTypes.NVARCHAR:
                return Optional.of(ColumnMapping.sliceMapping(
                        createVarcharType(typeHandle.getRequiredColumnSize()),
                        (varcharResultSet, varcharColumnIndex) -> utf8Slice(varcharResultSet.getString(varcharColumnIndex)),
                        varcharWriteFunction(),
                        FULL_PUSHDOWN));

            case OracleTypes.CLOB:
            case OracleTypes.NCLOB:
                return Optional.of(ColumnMapping.sliceMapping(
                        createUnboundedVarcharType(),
                        (resultSet, columnIndex) -> utf8Slice(resultSet.getString(columnIndex)),
                        varcharWriteFunction(),
                        DISABLE_PUSHDOWN));

            case OracleTypes.VARBINARY: // Oracle's RAW(n)
            case OracleTypes.BLOB:
                return Optional.of(ColumnMapping.sliceMapping(
                        VARBINARY,
                        (resultSet, columnIndex) -> wrappedBuffer(resultSet.getBytes(columnIndex)),
                        varbinaryWriteFunction(),
                        DISABLE_PUSHDOWN));

            case OracleTypes.TIMESTAMP:
                return Optional.of(ColumnMapping.longMapping(
                        TIMESTAMP_MILLIS,
                        oracleTimestampReadFunction(),
                        trinoTimestampToOracleTimestampWriteFunction(),
                        FULL_PUSHDOWN));
            case OracleTypes.TIMESTAMPTZ:
                return Optional.of(oracleTimestampWithTimeZoneColumnMapping());
        }
        if (getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR) {
            return mapToUnboundedVarchar(typeHandle);
        }
        return Optional.empty();
    }

    @Override
    public Optional<JdbcExpression> implementAggregation(ConnectorSession session, AggregateFunction aggregate, Map<String, ColumnHandle> assignments)
    {
        return aggregateFunctionRewriter.rewrite(session, aggregate, assignments);
    }

    private static Optional<JdbcTypeHandle> toTypeHandle(DecimalType decimalType)
    {
        return Optional.of(new JdbcTypeHandle(OracleTypes.NUMBER, Optional.of("NUMBER"), Optional.of(decimalType.getPrecision()), Optional.of(decimalType.getScale()), Optional.empty(), Optional.empty()));
    }

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> format("SELECT * FROM (%s) WHERE ROWNUM <= %s", sql, limit));
    }

    @Override
    public boolean isLimitGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    protected boolean isSupportedJoinCondition(ConnectorSession session, JdbcJoinCondition joinCondition)
    {
        return joinCondition.getOperator() != JoinCondition.Operator.IS_DISTINCT_FROM;
    }

    public static LongWriteFunction trinoDateToOracleDateWriteFunction()
    {
        return new LongWriteFunction()
        {
            @Override
            public String getBindExpression()
            {
                return "TO_DATE(?, 'SYYYY-MM-DD')";
            }

            @Override
            public void set(PreparedStatement statement, int index, long value)
                    throws SQLException
            {
                long utcMillis = DAYS.toMillis(value);
                LocalDateTime date = LocalDateTime.from(Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC));
                statement.setString(index, DATE_FORMATTER.format(date));
            }

            @Override
            public void setNull(PreparedStatement statement, int index)
                    throws SQLException
            {
                statement.setNull(index, Types.VARCHAR);
            }
        };
    }

    private static LongWriteFunction trinoTimestampToOracleDateWriteFunction()
    {
        return new LongWriteFunction()
        {
            @Override
            public String getBindExpression()
            {
                // Oracle's DATE stores year, month, day, hour, minute, seconds, but not second fraction
                return "TO_DATE(?, 'SYYYY-MM-DD HH24:MI:SS')";
            }

            @Override
            public void set(PreparedStatement statement, int index, long value)
                    throws SQLException
            {
                long epochSecond = floorDiv(value, MICROSECONDS_PER_SECOND);
                int microsOfSecond = floorMod(value, MICROSECONDS_PER_SECOND);
                verify(microsOfSecond == 0, "Micros of second must be zero: '%s'", value);
                LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC);
                statement.setString(index, TIMESTAMP_SECONDS_FORMATTER.format(localDateTime));
            }

            @Override
            public void setNull(PreparedStatement statement, int index)
                    throws SQLException
            {
                statement.setNull(index, Types.VARCHAR);
            }
        };
    }

    public static LongWriteFunction trinoTimestampToOracleTimestampWriteFunction()
    {
        return new LongWriteFunction()
        {
            @Override
            public String getBindExpression()
            {
                return "TO_TIMESTAMP(?, 'SYYYY-MM-DD HH24:MI:SS.FF')";
            }

            @Override
            public void set(PreparedStatement statement, int index, long utcMillis)
                    throws SQLException
            {
                long epochSecond = floorDiv(utcMillis, MICROSECONDS_PER_SECOND);
                int nanoFraction = floorMod(utcMillis, MICROSECONDS_PER_SECOND) * NANOSECONDS_PER_MICROSECOND;
                LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(epochSecond, nanoFraction, ZoneOffset.UTC);
                statement.setString(index, TIMESTAMP_MILLIS_FORMATTER.format(localDateTime));
            }

            @Override
            public void setNull(PreparedStatement statement, int index)
                    throws SQLException
            {
                statement.setNull(index, Types.VARCHAR);
            }
        };
    }

    private static LongReadFunction oracleTimestampReadFunction()
    {
        return (resultSet, columnIndex) -> {
            LocalDateTime timestamp = resultSet.getObject(columnIndex, LocalDateTime.class);
            // Adjust years when the value is B.C. dates because Oracle returns +1 year unless converting to string in their server side
            if (timestamp.getYear() <= 0) {
                timestamp = timestamp.minusYears(1);
            }
            return timestamp.toInstant(ZoneOffset.UTC).toEpochMilli() * MICROSECONDS_PER_MILLISECOND;
        };
    }

    public static ColumnMapping oracleTimestampWithTimeZoneColumnMapping()
    {
        return ColumnMapping.longMapping(
                TIMESTAMP_TZ_MILLIS,
                (resultSet, columnIndex) -> {
                    ZonedDateTime timestamp = resultSet.getObject(columnIndex, ZonedDateTime.class);
                    return packDateTimeWithZone(
                            timestamp.toInstant().toEpochMilli(),
                            timestamp.getZone().getId());
                },
                oracleTimestampWithTimeZoneWriteFunction(),
                FULL_PUSHDOWN);
    }

    public static LongWriteFunction oracleTimestampWithTimeZoneWriteFunction()
    {
        return LongWriteFunction.of(OracleTypes.TIMESTAMPTZ, (statement, index, encodedTimeWithZone) -> {
            Instant time = Instant.ofEpochMilli(unpackMillisUtc(encodedTimeWithZone));
            ZoneId zone = unpackZoneKey(encodedTimeWithZone).getZoneId();
            statement.setObject(index, time.atZone(zone));
        });
    }

    private static WriteMapping oracleBooleanWriteMapping()
    {
        return WriteMapping.booleanMapping("number(1)", oracleBooleanWriteFunction());
    }

    private static BooleanWriteFunction oracleBooleanWriteFunction()
    {
        return BooleanWriteFunction.of(Types.TINYINT, (statement, index, value) -> statement.setInt(index, value ? 1 : 0));
    }

    public static LongWriteFunction oracleRealWriteFunction()
    {
        return LongWriteFunction.of(Types.REAL, (statement, index, value) ->
                ((OraclePreparedStatement) statement).setBinaryFloat(index, intBitsToFloat(toIntExact(value))));
    }

    public static DoubleWriteFunction oracleDoubleWriteFunction()
    {
        return DoubleWriteFunction.of(Types.DOUBLE, (statement, index, value) ->
                ((OraclePreparedStatement) statement).setBinaryDouble(index, value));
    }

    private SliceWriteFunction oracleCharWriteFunction()
    {
        return SliceWriteFunction.of(Types.NCHAR, (statement, index, value) -> ((OraclePreparedStatement) statement).setFixedCHAR(index, value.toStringUtf8()));
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        if (type instanceof VarcharType varcharType) {
            String dataType;
            if (varcharType.isUnbounded() || varcharType.getBoundedLength() > ORACLE_VARCHAR2_MAX_CHARS) {
                dataType = "nclob";
            }
            else {
                dataType = "varchar2(" + varcharType.getBoundedLength() + " CHAR)";
            }
            return WriteMapping.sliceMapping(dataType, varcharWriteFunction());
        }
        if (type instanceof CharType charType) {
            String dataType;
            if (charType.getLength() > ORACLE_CHAR_MAX_CHARS) {
                dataType = "nclob";
            }
            else {
                dataType = "char(" + charType.getLength() + " CHAR)";
            }
            return WriteMapping.sliceMapping(dataType, oracleCharWriteFunction());
        }
        if (type instanceof DecimalType decimalType) {
            String dataType = format("number(%s, %s)", decimalType.getPrecision(), decimalType.getScale());
            if (decimalType.isShort()) {
                return WriteMapping.longMapping(dataType, shortDecimalWriteFunction(decimalType));
            }
            return WriteMapping.objectMapping(dataType, longDecimalWriteFunction(decimalType));
        }
        if (type.equals(TIMESTAMP_SECONDS)) {
            // Specify 'date' instead of 'timestamp(0)' to propagate the type in case of CTAS from date columns
            // Oracle date stores year, month, day, hour, minute, seconds, but not second fraction
            return WriteMapping.longMapping("date", trinoTimestampToOracleDateWriteFunction());
        }
        if (type.equals(TIMESTAMP_MILLIS)) {
            return WriteMapping.longMapping("timestamp(3)", trinoTimestampToOracleTimestampWriteFunction());
        }
        WriteMapping writeMapping = WRITE_MAPPINGS.get(type);
        if (writeMapping != null) {
            return writeMapping;
        }
        throw new TrinoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
    }

    @Override
    public void setColumnComment(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column, Optional<String> comment)
    {
        // Oracle doesn't support prepared statement for COMMENT statement
        String sql = format(
                "COMMENT ON COLUMN %s.%s IS %s",
                quoted(handle.asPlainTable().getRemoteTableName()),
                quoted(column.getColumnName()),
                varcharLiteral(comment.orElse("")));
        execute(session, sql);
    }

    @Override
    public void setColumnType(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column, Type type)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support setting column types");
    }
}
