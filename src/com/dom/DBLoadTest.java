package com.dom;

import oracle.jdbc.pool.OracleDataSource;
import org.apache.commons.cli.*;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/*
 * Copyright 2020 Dominic Giles. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static java.util.logging.Level.FINE;

public class DBLoadTest {

    private static final Logger logger = Logger.getLogger(DBLoadTest.class.getName());
    private static final String TABLE_NAME = "just_a_table";

    private enum CommandLineOptions {
        USERNAME,
        PASSWORD,
        CONNECT_STRING,
        ROWS_TO_INSERT,
        COMMIT_FREQUENCY,
        BATCH_SIZE,
        THREAD_COUNT,
        ASYNC,
        TEST_TYPE,
        TARGET_TYPE,
        BENCHMARK_TYPE,
        SELECT_COMMAND,
        OPERATIONS_TO_PERFORM,
        DATA_RANGE,
        OUTPUT_RESULTS
    }

    private enum OutputDestination {
        CSV, STDOUT;

        public static OutputDestination parseCLOption(String value) {
            switch (value) {
                case "csv":
                    return CSV;
                case "stdout":
                    return STDOUT;
                default:
                    throw new RuntimeException("Unrecognised output destination");
            }
        }
    }

    private enum TransactionType {
        RELATIONAL, DOCUMENT;

        public static TransactionType getValue(String value) {
            return valueOf(value.toUpperCase());
        }
    }

    private enum DBType {
        ORACLE, POSTGRESQL, MYSQL;
    }

    private enum BenchmarkTask {
        CREATE_TABLES, CREATE_INDEXS, DROP_INDEXES, INSERT, UPDATE, SELECT, MIXED, FULL_WORKLOAD, TABLE_SIZE;

        public static BenchmarkTask parseCLOption(String value) {
            switch (value) {
                case "i":
                    return INSERT;
                case "c":
                    return UPDATE;
                case "s":
                    return SELECT;
                case "m":
                    return MIXED;
                case "create":
                    return CREATE_TABLES;
                case "ci":
                    return CREATE_INDEXS;
                case "di":
                    return DROP_INDEXES;
                case "full":
                    return FULL_WORKLOAD;
                case "ts":
                    return TABLE_SIZE;
                default:
                    throw new RuntimeException("Unrecognised command line option");
            }
        }
    }

    private enum BenchmarkQuery {
        SIMPLE_LOOKUP(String.format("select * from %s where column1 = ?", TABLE_NAME)),
        SIMPLE_RANGE_SCAN(String.format("select * from %s where column7 between ? and ?", TABLE_NAME)),
        SIMPLE_COUNT(String.format("select count(*) from %s where column7 between ? and ?", TABLE_NAME));

        private final String sql;

        BenchmarkQuery(String sql) {
            this.sql = sql;
        }

        public String getSql() {
            return sql;
        }

        public static BenchmarkQuery parseCLOption(String sql) {
            switch (sql) {
                case "lookup":
                    return SIMPLE_LOOKUP;
                case "range_scan":
                    return SIMPLE_RANGE_SCAN;
                case "count":
                    return SIMPLE_COUNT;
                default:
                    throw new RuntimeException("Unrecognised sql operation option");
            }
        }
    }


    private static final String DROP_TABLE = String.format("DROP TABLE %s", TABLE_NAME);
    private static final String VACUUM_TABLE = String.format("VACUUM ANALYZE %s", TABLE_NAME);
    private static final String CREATE_TABLE = String.format("CREATE TABLE %s (\n" +
            "COLUMN1\t\tnumeric(20) \t    NOT NULL,\n" +
            "COLUMN2\t\tsmallint\t        NOT NULL,\n" +
            "COLUMN3\t\tinteger \t        NOT NULL,\n" +
            "COLUMN4\t\tdecimal\t         NOT NULL,\n" +
            "COLUMN5\t\treal \t           NOT NULL,\n" +
            "COLUMN6\t\tdouble precision  NOT NULL,\n" +
            "COLUMN7\t\tDATE\t            NOT NULL,\n" +
            "COLUMN8\t\ttimestamp\t       NOT NULL,\n" +
            "COLUMN9\t\tvarchar(10)\t     NOT NULL,\n" +
            "COLUMN10\t\tvarchar(50)\t    NOT NULL,\n" +
            "COLUMN11\t\tvarchar(100)\t   NOT NULL,\n" +
            "COLUMN12\t\tchar(1)\t        NOT NULL,\n" +
            "COLUMN13\t\tchar(10)\t       NOT NULL\n" +
            ")", TABLE_NAME);

    private static final String INSERT_STATEMENT = String.format("insert into %s(column1,column2,column3,column4,column5,column6,column7,column8,column9,column10,column11,column12,column13) values (?,?,?,?,?,?,?,?,?,?,?,?,?)", TABLE_NAME);
    private static final String UPDATE_STATEMENT = String.format("update %s set COLUMN4 = ?, COLUMN10 = ? where COLUMN1 = ?", TABLE_NAME);
    private static final String CREATE_PK_INDEX = String.format("ALTER TABLE %s ADD CONSTRAINT col1_pk PRIMARY KEY (column1)", TABLE_NAME);
    private static final String CREATE_DATE_INDEX = String.format("CREATE INDEX COL7_IDX ON %s(COLUMN7)", TABLE_NAME);
    private static final String CREATE_FLOAT_INDEX = String.format("CREATE INDEX COL4_IDX ON %s(COLUMN4)", TABLE_NAME);
    private static final String CREATE_VARCHAR_INDEX = String.format("CREATE INDEX COL10_IDX ON %s(COLUMN10)", TABLE_NAME);
    private static final String DROP_PK_IDX = String.format("ALTER TABLE %s drop constraint col1_pk", TABLE_NAME);
    private static final String DROP_DATE_IDX = "DROP INDEX COL7_IDX";
    private static final String DROP_FLOAT_IDX = "DROP INDEX COL4_IDX";
    private static final String DROP_VARCHAR_IDX = "DROP INDEX COL10_IDX";

    private static final String PG_TABLE_SIZE_SQL = String.format("SELECT\n" +
            "  table_schema,\n" +
            "  TABLE_NAME,\n" +
            "  row_estimate,\n" +
            "  pg_size_pretty(table_bytes) AS TABLE,\n" +
            "  pg_size_pretty(index_bytes) AS INDEX,\n" +
            "  pg_size_pretty(total_bytes) AS total\n" +
            "FROM (\n" +
            "       SELECT\n" +
            "         *,\n" +
            "         total_bytes - index_bytes - COALESCE(toast_bytes, 0) AS table_bytes\n" +
            "       FROM (\n" +
            "              SELECT\n" +
            "                c.oid,\n" +
            "                nspname                               AS table_schema,\n" +
            "                relname                               AS TABLE_NAME,\n" +
            "                c.reltuples                           AS row_estimate,\n" +
            "                pg_total_relation_size(c.oid)         AS total_bytes,\n" +
            "                pg_indexes_size(c.oid)                AS index_bytes,\n" +
            "                pg_total_relation_size(reltoastrelid) AS toast_bytes\n" +
            "              FROM pg_class c\n" +
            "                LEFT JOIN pg_namespace n ON n.oid = c.relnamespace\n" +
            "              WHERE relkind = 'r'\n" +
            "                    AND relname = '%s'\n" +
            "            ) a\n" +
            "     ) a\n" +
            "LIMIT 30;", TABLE_NAME);

    private static final String ORA_TABLE_SIZE_SQL = String.format("WITH details AS (SELECT\n" +
            "                   SYS_CONTEXT('USERENV', 'SESSION_USER') AS table_schema,\n" +
            "                   table_name,\n" +
            "                   num_rows                                  row_estimate\n" +
            "                 FROM user_tables\n" +
            "                 WHERE table_name = '%s'),\n" +
            "    table_size AS (SELECT\n" +
            "                     s.segment_name AS table_name,\n" +
            "                     SUM(s.bytes)      unformatted_size\n" +
            "                   FROM user_segments s\n" +
            "                   WHERE s.segment_name = '%s'\n" +
            "                   GROUP BY s.segment_name),\n" +
            "    index_size AS ( SELECT\n" +
            "                      '%s' table_name,\n" +
            "                      SUM(s.bytes)   unformatted_size\n" +
            "                    FROM user_segments s\n" +
            "                    WHERE s.segment_name IN (SELECT i.index_name\n" +
            "                                             FROM user_indexes i\n" +
            "                                             WHERE i.table_name = '%s'))\n" +
            "SELECT\n" +
            "  details.table_schema,\n" +
            "  details.table_name,\n" +
            "  details.row_estimate,\n" +
            "  format_size(table_size.unformatted_size) as table_size,\n" +
            "  format_size(index_size.unformatted_size) as index_size,\n" +
            "  format_size(table_size.unformatted_size + index_size.unformatted_size) AS total_size\n" +
            "FROM details, table_size, index_size\n", TABLE_NAME.toUpperCase(), TABLE_NAME.toUpperCase(), TABLE_NAME.toUpperCase(), TABLE_NAME.toUpperCase());


    private static Long maxId;

    private static String convertMilliseconds(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        millis -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        millis -= TimeUnit.MINUTES.toMillis(minutes);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        millis -= TimeUnit.SECONDS.toMillis(seconds);
        return String.format("%02d:%02d:%02d.%d", hours, minutes, seconds, millis);
    }

    private static Connection connect(String un, String pw, String cs, Boolean doAsync) throws RuntimeException, Error {
        try {
            OracleDataSource ods = new OracleDataSource();
            ods.setUser(un);
            ods.setPassword(pw);
            ods.setURL(cs);
            Properties connectionProperties = new Properties();
            connectionProperties.setProperty("autoCommit", "false");
            connectionProperties.setProperty("oracle.jdbc.fanEnabled", "false");
            ods.setConnectionProperties(connectionProperties);
            Connection connection = ods.getConnection();
            if (doAsync) {
                connection.createStatement().execute("ALTER SESSION SET COMMIT_WRITE = NOWAIT");
            }
            return connection;
        } catch (SQLException e) {
            logger.log(FINE, "SQL Exception Thown in connect()", e);
            throw new RuntimeException(e);
        }
    }

    private static Connection pconnect(String un, String pw, String cs, Boolean doAsync) throws RuntimeException, Error {
        try {
            PGSimpleDataSource pds = new PGSimpleDataSource();
            pds.setUser(un);
            pds.setPassword(pw);
            pds.setUrl(cs);
            Connection connection = pds.getConnection();
            connection.setAutoCommit(false);
            if (doAsync) {
                connection.createStatement().execute("SET synchronous_commit = off");
            }
            return connection;
        } catch (SQLException e) {
            logger.log(FINE, "SQL Exception Thown in pconnect()", e);
            throw new RuntimeException(e);
        }
    }

    private static void createTables(Connection connection) throws RuntimeException {
        try (Statement st = connection.createStatement();) {
            try {
                st.execute(DROP_TABLE);
                logger.fine(String.format("Table %s dropped", TABLE_NAME));
            } catch (Exception sqle) {
                logger.log(FINE, "Table hasn't been created yet");
                connection.commit(); // Don't like this but Postgresql aborts a transaction on exception
            }
            st.execute(CREATE_TABLE);
            logger.fine(String.format("Table %s created", TABLE_NAME));
            connection.commit();
        } catch (Exception e) {
            logger.log(FINE, "SQL Exception Thrown in createTables()", e);
            throw new RuntimeException(e);
        }
    }

    private static void createIndexes(Connection connection, Map<CommandLineOptions, Object> pclo) throws SQLException {
        try (Statement st = connection.createStatement();) {

            try {
                st.execute(CREATE_PK_INDEX);
                st.execute(CREATE_FLOAT_INDEX);
                st.execute(CREATE_DATE_INDEX);
                st.execute(CREATE_VARCHAR_INDEX);
                if (pclo.get(CommandLineOptions.TARGET_TYPE) == DBType.POSTGRESQL)
                    vacuumAndAnalyze(connection);
//                connection.commit();
            } catch (SQLException e) {
                logger.log(FINE, "SQL Exception Thrown in createIndexes()", e);
                throw new RuntimeException(e);
            }
        }
    }

    private static void dropIndexes(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();) {
            try {
                st.execute(DROP_PK_IDX);
                st.execute(DROP_FLOAT_IDX);
                st.execute(DROP_DATE_IDX);
                st.execute(DROP_VARCHAR_IDX);
                logger.fine(String.format("Indexes Dropped", TABLE_NAME));
                connection.commit();
            } catch (SQLException e) {
                logger.log(FINE, "SQL Exception Thrown in dropIndexes()", e);
                throw new RuntimeException(e);
            }
        }
    }

    private static void vacuumAndAnalyze(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();) {
            try {
                connection.setAutoCommit(true);
                st.execute(VACUUM_TABLE);
                connection.setAutoCommit(false);
                logger.fine(String.format("Table %s Vacuumed", TABLE_NAME));
                connection.commit();
            } catch (SQLException e) {
                logger.log(FINE, "SQL Exception Thrown in vacuumAndAnalyze()", e);
                throw new RuntimeException(e);
            }
        }
    }

    private static void doMixed(Connection connection, Long operations, Long dataRange, Long sleep) {
        try {
            LocalDate tenYearsAgo = LocalDate.now().minusYears(10);
            Random r = new Random();
            for (int x = 0; x < operations; x++) {
                int t = r.nextInt(3);
                switch (t) {
                    case 0:
                        try (PreparedStatement ps = (connection.prepareStatement(BenchmarkQuery.SIMPLE_LOOKUP.getSql()))) {
                            for (int i = 0; i < 10; i++) {
                                Long v = randomLong(1, dataRange);
                                ps.setLong(1, v);
                                try (ResultSet rs = ps.executeQuery()) {
                                    rs.next();
                                }
                            }
                        }
                        break;

                    case 1:
                        try (PreparedStatement ps = connection.prepareStatement(INSERT_STATEMENT)) {
                            for (int i = 0; i < 3; i++) {
                                ps.setLong(1, getNextVal());
                                ps.setInt(2, 9999);
                                ps.setInt(3, 999999999);
                                ps.setFloat(4, 9999999.99f);
                                ps.setFloat(5, 9999999.999999f);
                                ps.setFloat(6, 9999999.999999f);
                                ps.setDate(7, java.sql.Date.valueOf(randomDate(tenYearsAgo, 3650)));
                                ps.setTimestamp(8, Timestamp.valueOf(randomDate(tenYearsAgo, 3650).atStartOfDay()));
                                ps.setString(9, "Hello");
                                ps.setString(10, "World!!");
                                ps.setString(11, "Hello World!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                                ps.setString(12, "H");
                                ps.setString(13, "HelloWorld");
                                ps.executeUpdate();
                            }

                        }
                        break;
                    case 2:
                        try (PreparedStatement ps = connection.prepareStatement(UPDATE_STATEMENT)) {
                            for (int i = 0; i < 2; i++) {
                                ps.setDouble(1, randomInteger(1, 1000000000) + 0.1);
                                ps.setString(2, String.format("%" + randomInteger(1, 48) + "s", " ").replace(' ', '*'));
                                ps.setLong(3, randomLong(1, dataRange));
                                ps.executeUpdate();
                            }
                        }
                }
                connection.commit();
                if (sleep > 0)
                    Thread.sleep(randomLong(0, sleep));
            }

        } catch (Exception e) {
            logger.log(FINE, "Exception Thrown in start() : ", e);
        }
    }


    private static void doInserts(Connection connection, Long rowsToInsert, Long batchsize, Long commitFrequency) throws RuntimeException, Error {
        try {
            LocalDate tenYearsAgo = LocalDate.now().minusYears(10);
            try (PreparedStatement ps = connection.prepareStatement(INSERT_STATEMENT)) {
                for (int i = 0; i < rowsToInsert; i++) {
                    ps.setLong(1, getNextVal());
                    ps.setInt(2, 9999);
                    ps.setInt(3, 999999999);
                    ps.setFloat(4, 9999999.99f);
                    ps.setFloat(5, 9999999.999999f);
                    ps.setFloat(6, 9999999.999999f);
                    ps.setDate(7, java.sql.Date.valueOf(randomDate(tenYearsAgo, 3650)));
                    ps.setTimestamp(8, Timestamp.valueOf(randomDate(tenYearsAgo, 3650).atStartOfDay()));
                    ps.setString(9, "Hello");
                    ps.setString(10, "World!!");
                    ps.setString(11, "Hello World!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                    ps.setString(12, "H");
                    ps.setString(13, "HelloWorld");
                    if (batchsize != -1) {
                        ps.addBatch();
                    } else {
                        ps.executeUpdate();
                    }
                    if (i % batchsize == 0) {
                        ps.executeBatch();
                    }
                    if (i % commitFrequency == 0) {
                        connection.commit();
                    }

                }
                if (batchsize != -1) {
                    ps.executeBatch();
                }
                connection.commit();
            }
        } catch (SQLException e) {
            logger.log(FINE, "Exception Thrown in start() : ", e);
        }
    }

    private static Long getMaxId(Connection connection) throws Exception {
        Long val;
        try (PreparedStatement ps = connection.prepareStatement(String.format("select max(COLUMN1) from %s", TABLE_NAME))) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                val = rs.getLong(1);
            }
        }
        return val + 1;
    }


    private static ReentrantLock lock = new ReentrantLock();

    private static Long getNextVal() {
        Long val;
        try {
            lock.lock();
            maxId += 1;
            val = maxId;
        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
        return val;
    }

    //"update %s set COLUMN4 = ?, COLUMN10 = ? where COLUMN1 = ?"
    private static void doUpdates(Connection connection, Long operations, Long dataRange, Long batchsize, Long commitFrequency) throws RuntimeException, Error {
        try {
            try (PreparedStatement ps = connection.prepareStatement(UPDATE_STATEMENT)) {
                for (int i = 0; i < operations; i++) {
                    ps.setDouble(1, randomInteger(1, 1000000000) + 0.1);
                    ps.setString(2, String.format("%" + randomInteger(1, 48) + "s", " ").replace(' ', '*'));
                    ps.setLong(3, randomLong(1, dataRange));
                    if (batchsize != -1) {
                        ps.addBatch();
                    } else {
                        ps.executeUpdate();
                    }
                    if (i % batchsize == 0) {
                        ps.executeBatch();
                    }
                    if (i % commitFrequency == 0) {
                        connection.commit();
                    }

                }
                if (batchsize != -1) {
                    ps.executeBatch();
                }
                connection.commit();
            }
        } catch (SQLException e) {
            logger.log(FINE, "Exception Thrown in start() : ", e);
        }
    }


    public static Connection getConnection(Map<CommandLineOptions, Object> pclo) {
        Connection connection;
        if (pclo.get(CommandLineOptions.TARGET_TYPE) == DBType.ORACLE) {
            connection = connect((String) pclo.get(CommandLineOptions.USERNAME),
                    (String) pclo.get(CommandLineOptions.PASSWORD),
                    String.format("jdbc:oracle:thin:@%s", (String) pclo.get(CommandLineOptions.CONNECT_STRING)),
                    (Boolean) pclo.get(CommandLineOptions.ASYNC));
            return connection;
        } else {
            connection = pconnect((String) pclo.get(CommandLineOptions.USERNAME),
                    (String) pclo.get(CommandLineOptions.PASSWORD),
                    String.format("jdbc:postgresql:%s", (String) pclo.get(CommandLineOptions.CONNECT_STRING)),
                    (Boolean) pclo.get(CommandLineOptions.ASYNC));
            return connection;
        }
    }

    private static List<Object[]> connectBenchmark(Map<CommandLineOptions, Object> pclo) throws Exception {

        List<Callable<Object[]>> connectTests = new ArrayList<>();
        for (int i = 0; i < (Integer) pclo.get(CommandLineOptions.THREAD_COUNT); i++) {
            Callable<Object[]> connectTask = () -> {
                Long start = System.currentTimeMillis();
                return new Object[]{getConnection(pclo), System.currentTimeMillis() - start, 0};
            };
            connectTests.add(connectTask);
        }
        ExecutorService executor = Executors.newWorkStealingPool();
        List<Object[]> connectResults;
        connectResults = executor.invokeAll(connectTests).stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList());
        return connectResults;
    }

    private static void closeConnections(List<Object[]> connections) throws Exception {
        connections.stream().forEach(o -> {
            try {
                ((Connection) (o[0])).close();
            } catch (SQLException ignored) {
            }
        });
    }

    private static List<Long> insertBenchmark(Map<CommandLineOptions, Object> pclo, List<Object[]> connectionList) throws Exception {

        int threadCount = (Integer) pclo.get(CommandLineOptions.THREAD_COUNT);
        Long rowsToInsert = (Long) pclo.get(CommandLineOptions.ROWS_TO_INSERT);
        Long rowsToInsertPerThread = rowsToInsert / threadCount;

        List<Callable<Long>> insertTests = new ArrayList<>();
        for (Object[] connectionResult : connectionList) {
            Callable<Long> insertTask = () -> {
                Long start = System.currentTimeMillis();
                doInserts((Connection) connectionResult[0],
                        rowsToInsertPerThread,
                        (Long) pclo.get(CommandLineOptions.BATCH_SIZE),
                        (Long) pclo.get(CommandLineOptions.COMMIT_FREQUENCY));
                return System.currentTimeMillis() - start;
            };
            insertTests.add(insertTask);
        }

        ExecutorService executor = Executors.newWorkStealingPool();
        return executor.invokeAll(insertTests).stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList());
    }

    private static List<Long> updateBenchmark(Map<CommandLineOptions, Object> pclo, List<Object[]> connectionList) throws Exception {

        int threadCount = (Integer) pclo.get(CommandLineOptions.THREAD_COUNT);
        Long operations = (Long) pclo.get(CommandLineOptions.OPERATIONS_TO_PERFORM);
        Long dataRange = (Long) pclo.get(CommandLineOptions.DATA_RANGE);
        Long operationsPerThread = operations / threadCount;

        List<Callable<Long>> updateTests = new ArrayList<>();
        for (Object[] connectionResult : connectionList) {
            Callable<Long> updateTask = () -> {
                Long start = System.currentTimeMillis();
                doUpdates((Connection) connectionResult[0],
                        operationsPerThread,
                        dataRange,
                        (Long) pclo.get(CommandLineOptions.BATCH_SIZE),
                        (Long) pclo.get(CommandLineOptions.COMMIT_FREQUENCY));
                return System.currentTimeMillis() - start;
            };
            updateTests.add(updateTask);
        }

        ExecutorService executor = Executors.newWorkStealingPool();
        return executor.invokeAll(updateTests).stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList());
    }

    private static List<Long> mixedBenchmark(Map<CommandLineOptions, Object> pclo, List<Object[]> connectionList) throws Exception {

        int threadCount = (Integer) pclo.get(CommandLineOptions.THREAD_COUNT);
        Long operations = (Long) pclo.get(CommandLineOptions.OPERATIONS_TO_PERFORM);
        Long dataRange = (Long) pclo.get(CommandLineOptions.DATA_RANGE);
        Long operationsPerThread = operations / threadCount;

        List<Callable<Long>> mixedTests = new ArrayList<>();
        for (Object[] connectionResult : connectionList) {
            Callable<Long> mixedTask = () -> {
                Long start = System.currentTimeMillis();
                doMixed((Connection) connectionResult[0],
                        operationsPerThread,
                        dataRange,
                        0L);
                return System.currentTimeMillis() - start;
            };
            mixedTests.add(mixedTask);
        }

        ExecutorService executor = Executors.newWorkStealingPool();
        return executor.invokeAll(mixedTests).stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList());
    }

    private static List<Long> selectBenchmark(Map<CommandLineOptions, Object> pclo, List<Object[]> connectionList) throws Exception {

        List<Callable<Long>> selectTests = new ArrayList<>();

        Integer threadCount = (Integer) pclo.get(CommandLineOptions.THREAD_COUNT);
        Long selectsToPerform = (Long) pclo.get(CommandLineOptions.OPERATIONS_TO_PERFORM);
        Long selectsToPerformPerThread = selectsToPerform / threadCount;
        Long dataRange = (Long) pclo.get(CommandLineOptions.DATA_RANGE);
        LocalDate tenYearsAgo = LocalDate.now().minusYears(10);
        BenchmarkQuery bmq = (BenchmarkQuery) pclo.get(CommandLineOptions.SELECT_COMMAND);
        for (Object[] connectionObject : connectionList) {
            Callable<Long> selectTask = () -> {
                Long start = System.currentTimeMillis();
                if (bmq == BenchmarkQuery.SIMPLE_LOOKUP) {
                    try (PreparedStatement ps = ((Connection) connectionObject[0]).prepareStatement(bmq.getSql())) {
                        for (int i = 0; i < selectsToPerformPerThread; i++) {
                            Long v = randomLong(1, dataRange);
                            ps.setLong(1, v);
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                            }
                        }
                    }
                } else if (bmq == BenchmarkQuery.SIMPLE_RANGE_SCAN || bmq == BenchmarkQuery.SIMPLE_COUNT) {
                    try (PreparedStatement ps = ((Connection) connectionObject[0]).prepareStatement(bmq.getSql())) {
                        LocalDate rd;
                        for (int i = 0; i < selectsToPerformPerThread; i++) {
                            rd = randomDate(tenYearsAgo, 3650);
                            ps.setDate(1, java.sql.Date.valueOf(rd));
                            ps.setDate(2, java.sql.Date.valueOf(rd.plusDays(3)));
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
//                                printResults(rs);
                            }
                        }

                    }
                }
                return System.currentTimeMillis() - start;
            };
            selectTests.add(selectTask);
        }
        ExecutorService executor = Executors.newWorkStealingPool();
        return executor.invokeAll(selectTests).stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList());
    }

    private static String[] getResultString(String description, Long connectionTime, Long operations, Long timeTaken, Map<CommandLineOptions, Object> pclo) {
        return new String[]{description,
                Long.toString(operations),
                String.format("%.0f", operations / (timeTaken / 1000d)),
                convertMilliseconds(connectionTime),
                convertMilliseconds(timeTaken),
                pclo.get(CommandLineOptions.THREAD_COUNT).toString(),
                pclo.get(CommandLineOptions.TARGET_TYPE).toString(),
                pclo.get(CommandLineOptions.COMMIT_FREQUENCY).toString(),
                pclo.get(CommandLineOptions.BATCH_SIZE).toString(),
                pclo.get(CommandLineOptions.ASYNC).toString()};
    }

    private static void renderResults(String description, Long connectionTime, Long operations, Long timeTaken, Map<CommandLineOptions, Object> pclo) {
        AsciiTable table = new AsciiTable();
        table.addColumns(new String[]{"Test Name", "Operations", "Operations/sec", "Connection Time", "Total Time", "Threads", "Target", "Commits", "Batch", "Async"});
        table.addRow(getResultString(description, connectionTime, operations, timeTaken, pclo));
        table.calculateColumnWidth();
        System.out.println(table.render());
    }


    private static List<String[]> doInsertsTests(Map<CommandLineOptions, Object> pclo, Integer[] threadWorkload) throws Exception {
        List<String[]> results = new ArrayList<>();
        final Long rowsToInsert = 1000000L;
        pclo.put(CommandLineOptions.BENCHMARK_TYPE, BenchmarkTask.INSERT);
        pclo.put(CommandLineOptions.THREAD_COUNT, 1);
        pclo.put(CommandLineOptions.ROWS_TO_INSERT, rowsToInsert);
        pclo.put(CommandLineOptions.COMMIT_FREQUENCY, 1000L);
        pclo.put(CommandLineOptions.BATCH_SIZE, 100L);
        pclo.put(CommandLineOptions.ASYNC, Boolean.FALSE);
        long connectionTime;
        long start;
        long timeTaken;
        List<Object[]> connectInfo;
        for (Integer threadCount : threadWorkload) {
            pclo.put(CommandLineOptions.THREAD_COUNT, threadCount);
            start = System.currentTimeMillis();
            connectInfo = connectBenchmark(pclo);
            connectionTime = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
            insertBenchmark(pclo, connectInfo);
            timeTaken = System.currentTimeMillis() - start;
            results.add(getResultString("Ingest via DML", connectionTime, rowsToInsert, timeTaken, pclo));
            closeConnections(connectInfo);
        }
        return results;
    }

    private static List<String[]> doUpdateTests(Map<CommandLineOptions, Object> pclo, Integer[] threadWorkload) throws Exception {
        List<String[]> results = new ArrayList<>();
        final Long operationsToPerform = 10000L;
        pclo.put(CommandLineOptions.BENCHMARK_TYPE, BenchmarkTask.UPDATE);
        pclo.put(CommandLineOptions.THREAD_COUNT, 1);
        pclo.put(CommandLineOptions.COMMIT_FREQUENCY, 10L);
        pclo.put(CommandLineOptions.BATCH_SIZE, 5L);
        pclo.put(CommandLineOptions.ASYNC, Boolean.FALSE);
        pclo.put(CommandLineOptions.OPERATIONS_TO_PERFORM, operationsToPerform);
        pclo.put(CommandLineOptions.SELECT_COMMAND, BenchmarkQuery.SIMPLE_LOOKUP);
        pclo.put(CommandLineOptions.DATA_RANGE, maxId);
        long connectionTime;
        long start;
        long timeTaken;
        List<Object[]> connectInfo;
        for (Integer threadCount : threadWorkload) {
            pclo.put(CommandLineOptions.THREAD_COUNT, Integer.valueOf(threadCount));
            start = System.currentTimeMillis();
            connectInfo = connectBenchmark(pclo);
            connectionTime = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
            updateBenchmark(pclo, connectInfo);
            timeTaken = System.currentTimeMillis() - start;
            results.add(getResultString("Update Tests", connectionTime, operationsToPerform, timeTaken, pclo));
            closeConnections(connectInfo);
        }
        return results;
    }

    private static List<String[]> doMixedTests(Map<CommandLineOptions, Object> pclo, Integer[] threadWorkload) throws Exception {
        List<String[]> results = new ArrayList<>();
        final Long operationsToPerform = 10000L;
        pclo.put(CommandLineOptions.BENCHMARK_TYPE, BenchmarkTask.MIXED);
        pclo.put(CommandLineOptions.THREAD_COUNT, 1);
        pclo.put(CommandLineOptions.OPERATIONS_TO_PERFORM, operationsToPerform);
        pclo.put(CommandLineOptions.SELECT_COMMAND, BenchmarkQuery.SIMPLE_LOOKUP);
        pclo.put(CommandLineOptions.DATA_RANGE, maxId);
        long connectionTime;
        long start;
        long timeTaken;
        List<Object[]> connectInfo;
        for (Integer threadCount : threadWorkload) {
            pclo.put(CommandLineOptions.THREAD_COUNT, threadCount);
            start = System.currentTimeMillis();
            connectInfo = connectBenchmark(pclo);
            connectionTime = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
            mixedBenchmark(pclo, connectInfo);
            timeTaken = System.currentTimeMillis() - start;
            results.add(getResultString("Mixed Workload Tests", connectionTime, operationsToPerform, timeTaken, pclo));
            closeConnections(connectInfo);
        }
        return results;
    }

    private static List<String[]> doSelectTests(Map<CommandLineOptions, Object> pclo, Integer[] threadWorkload) throws Exception {
        List<String[]> results = new ArrayList<>();
        final Long operationsToPerform = 10000L;
        pclo.put(CommandLineOptions.BENCHMARK_TYPE, BenchmarkTask.SELECT);
        pclo.put(CommandLineOptions.THREAD_COUNT, 1);
        pclo.put(CommandLineOptions.OPERATIONS_TO_PERFORM, operationsToPerform);
        pclo.put(CommandLineOptions.SELECT_COMMAND, BenchmarkQuery.SIMPLE_LOOKUP);
        pclo.put(CommandLineOptions.DATA_RANGE, maxId);
        long connectionTime;
        long start;
        long timeTaken;
        List<Object[]> connectInfo;
        for (Integer threadCount : threadWorkload) {
            pclo.put(CommandLineOptions.THREAD_COUNT, threadCount);
            start = System.currentTimeMillis();
            connectInfo = connectBenchmark(pclo);
            connectionTime = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
            selectBenchmark(pclo, connectInfo);
            timeTaken = System.currentTimeMillis() - start;
            results.add(getResultString(String.format("Select : %s", pclo.get(CommandLineOptions.SELECT_COMMAND)), connectionTime, operationsToPerform, timeTaken, pclo));
            closeConnections(connectInfo);
        }
        pclo.put(CommandLineOptions.SELECT_COMMAND, BenchmarkQuery.SIMPLE_RANGE_SCAN);
        for (Integer threadCount : threadWorkload) {
            pclo.put(CommandLineOptions.THREAD_COUNT, threadCount);
            start = System.currentTimeMillis();
            connectInfo = connectBenchmark(pclo);
            connectionTime = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
            selectBenchmark(pclo, connectInfo);
            timeTaken = System.currentTimeMillis() - start;
            results.add(getResultString(String.format("Select : %s", pclo.get(CommandLineOptions.SELECT_COMMAND)), connectionTime, operationsToPerform, timeTaken, pclo));
            closeConnections(connectInfo);
        }
        pclo.put(CommandLineOptions.SELECT_COMMAND, BenchmarkQuery.SIMPLE_COUNT);
        for (Integer threadCount : threadWorkload) {
            pclo.put(CommandLineOptions.THREAD_COUNT, threadCount);
            start = System.currentTimeMillis();
            connectInfo = connectBenchmark(pclo);
            connectionTime = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
            selectBenchmark(pclo, connectInfo);
            timeTaken = System.currentTimeMillis() - start;
            results.add(getResultString(String.format("Select : %s", pclo.get(CommandLineOptions.SELECT_COMMAND)), connectionTime, operationsToPerform, timeTaken, pclo));
            closeConnections(connectInfo);
        }
        return results;
    }


    private static void runFullWorkload(Map<CommandLineOptions, Object> pclo) throws Exception {
        AsciiTable table = new AsciiTable();
        long start = System.currentTimeMillis();
        Connection connection = getConnection(pclo);
        OutputDestination output = (OutputDestination) pclo.get(CommandLineOptions.OUTPUT_RESULTS);
        createTables(connection);
        DBType dbType = (DBType) pclo.get(CommandLineOptions.TARGET_TYPE);
        table.addColumns(new String[]{"Test Name", "Total Time(ms)", "Target"});
        table.addRow(new String[]{"Create Tables", String.format("%d", System.currentTimeMillis() - start), pclo.get(CommandLineOptions.TARGET_TYPE).toString()});
        table.calculateColumnWidth();
        if (output == OutputDestination.STDOUT)
            System.out.println(table.render());
        else {
            Files.write(Paths.get(String.format("%s_create_tables.csv", dbType.toString())), table.renderAsCSV().getBytes());
        }
        List<String[]> results;
        maxId = getMaxId(connection);
        // Insert Data
        table = new AsciiTable();
        table.addColumns(new String[]{"Test Name", "Operations", "Operations/sec", "Connection Time", "Total Time", "Threads", "Target", "Commits", "Batch", "Async"});
        results = doInsertsTests(pclo, new Integer[]{1, 5, 10, 25, 50});
        for (String[] r : results)
            table.addRow(r);
        table.calculateColumnWidth();
        if (output == OutputDestination.STDOUT)
            System.out.println(table.render());
        else {
            Files.write(Paths.get(String.format("%s_ingest.csv", dbType.toString())), table.renderAsCSV().getBytes());
        }
        // Create Indexes
        table = new AsciiTable();
        start = System.currentTimeMillis();
        createIndexes(connection, pclo);
        table.addColumns(new String[]{"Test Name", "Total Time(ms)", "Target"});
        table.addRow(new String[]{"Create Indexes", String.format("%,d", System.currentTimeMillis() - start), pclo.get(CommandLineOptions.TARGET_TYPE).toString()});
        table.calculateColumnWidth();
        if (output == OutputDestination.STDOUT)
            System.out.println(table.render());
        else {
            Files.write(Paths.get(String.format("%s_indexes.csv", dbType.toString())), table.renderAsCSV().getBytes());
        }
        maxId = getMaxId(connection);
        // Run Select Tests
        table = new AsciiTable();
        table.addColumns(new String[]{"Test Name", "Operations", "Operations/sec", "Connection Time", "Total Time", "Threads", "Target", "Commits", "Batch", "Async"});
        results = doSelectTests(pclo, new Integer[]{1, 5, 10, 25, 50});
        for (String[] r : results)
            table.addRow(r);
        table.calculateColumnWidth();
        if (output == OutputDestination.STDOUT)
            System.out.println(table.render());
        else {
            Files.write(Paths.get(String.format("%s_selects.csv", dbType.toString())), table.renderAsCSV().getBytes());
        }
        // Update Tests
        table = new AsciiTable();
        table.addColumns(new String[]{"Test Name", "Operations", "Operations/sec", "Connection Time", "Total Time", "Threads", "Target", "Commits", "Batch", "Async"});
        results = doUpdateTests(pclo, new Integer[]{1, 5, 10, 25, 50});
        for (String[] r : results)
            table.addRow(r);
        table.calculateColumnWidth();
        if (output == OutputDestination.STDOUT)
            System.out.println(table.render());
        else {
            Files.write(Paths.get(String.format("%s_updates.csv", dbType.toString())), table.renderAsCSV().getBytes());
        }
        // Mixed Workload
        table = new AsciiTable();
        table.addColumns(new String[]{"Test Name", "Operations", "Operations/sec", "Connection Time", "Total Time", "Threads", "Target", "Commits", "Batch", "Async"});
        results = doMixedTests(pclo, new Integer[]{1, 5, 10, 25, 50});
        for (String[] r : results)
            table.addRow(r);
        table.calculateColumnWidth();
        if (output == OutputDestination.STDOUT)
            System.out.println(table.render());
        else {
            Files.write(Paths.get(String.format("%s_mixed.csv", dbType.toString())), table.renderAsCSV().getBytes());
        }
    }


    public static void main(String[] args) {
        try {
            Map<CommandLineOptions, Object> pclo = parseCommandLine(args);
            BenchmarkTask benchmark = (BenchmarkTask) pclo.get(CommandLineOptions.BENCHMARK_TYPE);
            if ((benchmark != BenchmarkTask.CREATE_TABLES) &&
                    (benchmark != BenchmarkTask.CREATE_INDEXS) &&
                    (benchmark != BenchmarkTask.DROP_INDEXES) &&
                    (benchmark != BenchmarkTask.FULL_WORKLOAD) &&
                    (benchmark != BenchmarkTask.TABLE_SIZE)) {

                int threadCount = (Integer) pclo.get(CommandLineOptions.THREAD_COUNT);
                Long rowsToInsert = (Long) pclo.get(CommandLineOptions.ROWS_TO_INSERT);
                Long rowsToInsertPerThread = rowsToInsert / threadCount;


                long startMillis = System.currentTimeMillis();

                List<Object[]> connectResults = connectBenchmark(pclo);
                OptionalDouble avgConnectTime = connectResults.stream().mapToLong(r -> (Long) r[1]).average();
                long connectionTime = System.currentTimeMillis() - startMillis;
                logger.fine(String.format("Connected all the threads, Average connect time = %f, Total Real Time to Connect = %d", avgConnectTime.orElse(0), connectionTime));

                maxId = getMaxId((Connection) connectResults.get(0)[0]);
                startMillis = System.currentTimeMillis();
                long opTime = 0;
                String taskDescription = "";
                if (benchmark == BenchmarkTask.INSERT) {
                    logger.fine(String.format("Asking all %d threads to insert %d rows each into the table %s", threadCount, rowsToInsertPerThread, TABLE_NAME));
                    List<Long> benchmarkResults = insertBenchmark(pclo, connectResults);
                    renderResults("Ingest via DML", connectionTime, rowsToInsert, System.currentTimeMillis() - startMillis, pclo);
                    OptionalDouble avgUpdateTime = benchmarkResults.stream().mapToLong(r -> r).average();
                } else if (benchmark == BenchmarkTask.UPDATE) {
                    Long updatesToPerform = (Long) pclo.get(CommandLineOptions.OPERATIONS_TO_PERFORM);
                    List<Long> benchmarkResults = updateBenchmark(pclo, connectResults);
                    renderResults("Update Workload", connectionTime, updatesToPerform, System.currentTimeMillis() - startMillis, pclo);
                    OptionalDouble avgUpdateTime = benchmarkResults.stream().mapToLong(r -> r).average();
                    logger.fine(String.format("Finished Update tests : Average Total Update Time/thread = %,.2f, Total Time to Update Across All Threads = %,d", avgUpdateTime.orElse(0), opTime));
                } else if (benchmark == BenchmarkTask.SELECT) {
                    Long selectsToPerform = (Long) pclo.get(CommandLineOptions.OPERATIONS_TO_PERFORM);
                    List<Long> benchmarkResults = selectBenchmark(pclo, connectResults);
                    renderResults("Select Workload", connectionTime, selectsToPerform, System.currentTimeMillis() - startMillis, pclo);
                    OptionalDouble avgSelectTime = benchmarkResults.stream().mapToLong(r -> r).average();
                    logger.fine(String.format("Finished Select tests : Average Total Select Time/thread = %,.0f, Total Time Select Time Across All Threads = %,d", avgSelectTime.orElse(0), opTime));
                } else if (benchmark == BenchmarkTask.MIXED) {
                    Long mixedToPerform = (Long) pclo.get(CommandLineOptions.OPERATIONS_TO_PERFORM);
                    List<Long> benchmarkResults = mixedBenchmark(pclo, connectResults);
                    renderResults("Mixed Workload", connectionTime, mixedToPerform, System.currentTimeMillis() - startMillis, pclo);
                    OptionalDouble avgSelectTime = benchmarkResults.stream().mapToLong(r -> r).average();
                    logger.fine(String.format("Finished Mixed tests : Average Total Mixed Ops Time/thread = %,.0f, Total Time Mixed Ops Time Across All Threads = %,d", avgSelectTime.orElse(0), opTime));
                }
            } else if (benchmark == BenchmarkTask.FULL_WORKLOAD) {
                runFullWorkload(pclo);
            } else if (benchmark == BenchmarkTask.CREATE_TABLES) {
                Connection connection = getConnection(pclo);
                createTables(connection);
            } else if (benchmark == BenchmarkTask.CREATE_INDEXS) {
                Connection connection = getConnection(pclo);
                long start = System.currentTimeMillis();
                createIndexes(connection, pclo);
                AsciiTable table = new AsciiTable();
                table.addColumns(new String[]{"Test Name", "Total Time", "Target"});
                table.addRow(new String[]{"Create Indexes", convertMilliseconds(System.currentTimeMillis() - start), pclo.get(CommandLineOptions.TARGET_TYPE).toString()});
                table.calculateColumnWidth();
                System.out.println(table.render());
            } else if (benchmark == BenchmarkTask.DROP_INDEXES) {
                Connection connection = getConnection(pclo);
                dropIndexes(connection);
            } else if (benchmark == BenchmarkTask.TABLE_SIZE) {
                Connection connection = getConnection(pclo);
                tableSizes(connection, pclo);
            }


        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected Exception thrown and not handled : ", e);
        }
    }

    private static void tableSizes(Connection connection, Map<CommandLineOptions, Object> pclo) {
        String sqlStatement = "";
        String schema = "";
        String tab = "";
        String rows = "";
        String iSize = "";
        String tSize = "";
        String totSize = "";

        try {
            if (pclo.get(CommandLineOptions.TARGET_TYPE) == DBType.ORACLE) {
                sqlStatement = ORA_TABLE_SIZE_SQL;
            } else {
                sqlStatement = PG_TABLE_SIZE_SQL;
            }
            try (PreparedStatement ps = connection.prepareStatement(sqlStatement);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    schema = rs.getString(1);
                    tab = rs.getString(2);
                    rows = rs.getString(3);
                    tSize = rs.getString(4);
                    iSize = rs.getString(5);
                    totSize = rs.getString(6);
                }
            }
        } catch (SQLException e) {
            logger.log(FINE, "SQL Exception Thown in connect()", e);
            throw new RuntimeException(e);
        }
        AsciiTable table = new AsciiTable();
        table.addColumns(new String[]{"Tables Schema", "Table Name", "Row Estimate", "Table Size", "Index Size", "Total Size"});
        table.addRow(new String[]{schema, tab, rows, tSize, iSize, totSize});
        table.calculateColumnWidth();
        System.out.println(table.render());
    }


    private static Map<CommandLineOptions, Object> parseCommandLine(String[] arguments) {
        Map<CommandLineOptions, Object> parsedOptions = new HashMap<>();

        Options options = new Options();
        OptionGroup optionGroup = new OptionGroup();
//        optionGroup.setRequired(true);
        Option option1 = new Option("i", "run insert workload");
        Option option2 = new Option("c", "run update workload");
        Option option3 = new Option("s", "run select workload");
        Option option4 = new Option("m", "run mixed workload");
        Option option22 = new Option("ci", "create indexes");
        Option option23 = new Option("di", "drop indexes");
        Option option18 = new Option("create", "create tables");
        Option option27 = new Option("ts", "table sizes");
        Option option24 = new Option("full", "full workload run");
        optionGroup.addOption(option1).addOption(option2).addOption(option3).addOption(option4).addOption(option22).addOption(option23).addOption(option18).addOption(option24).addOption(option27);
        options.addOptionGroup(optionGroup);
        Option option8 = new Option("u", "username");
        option8.setRequired(true);
        option8.setArgName("username");
        option8.setArgs(1);
        Option option9 = new Option("p", "password");
        option9.setArgs(1);
        option9.setRequired(true);
        option9.setArgName("password");
        Option option10 = new Option("cs", "connect string");
        option10.setArgs(1);
        option10.setRequired(true);
        option10.setArgName("connectstring");
        Option option11 = new Option("rc", "row count, defaults to 100");
        option11.setArgs(1);
        option11.setArgName("rowcount");
        Option option12 = new Option("cf", "commit frequency, defaults to 1");
        option12.setArgs(1);
        option12.setArgName("commitfrequency");
        Option option13 = new Option("bs", "batch size, defaults to 1");
        option13.setArgs(1);
        option13.setArgName("batchsize");
        Option option14 = new Option("tc", "thread count, defaults to 1");
        option14.setArgs(1);
        option14.setArgName("threadcount");
        Option option15 = new Option("async", "run async transactions, defaults to false");
        option15.setArgs(0);
        Option option16 = new Option("st", "benchmark test, relational or document");
        option16.setArgs(1);
        option16.setArgName("type");
        Option option17 = new Option("t", "target oracle or postgresql");
        option17.setArgs(1);
        option17.setArgName("db");
        Option option19 = new Option("sql", "which select statement to run (choices are : lookup,range_scan,count)");
        option19.setArgs(1);
        option19.setArgName("select_type");
        Option option20 = new Option("ops", "operations to perform i.e. select, updates");
        option20.setArgs(1);
        option20.setArgName("operations");
        Option option21 = new Option("dr", "data range : the maximum value of lookups");
        option21.setArgs(1);
        option21.setArgName("range");
        Option option25 = new Option("o", "output : valid values are stdout,csv");
        option25.setArgs(1);
        option25.setArgName("output");


        Option option30 = new Option("debug", "turn on debugging. Written to standard out");

        options.addOption(option8).addOption(option9).addOption(option10).addOption(option30).
                addOption(option11).addOption(option12).addOption(option13).addOption(option14).
                addOption(option15).addOption(option16).addOption(option17).addOption(option18).
                addOption(option19).addOption(option20).addOption(option21).addOption(option25);
        CommandLineParser clp = new DefaultParser();
        CommandLine cl;
        try {
            cl = clp.parse(options, arguments);
            if (cl.hasOption("debug")) {
                try {
                    System.setProperty("java.util.logging.config.class", "com.dom.LoggerConfig");
                    LogManager.getLogManager().readConfiguration();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (cl.hasOption("h")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("parameters:", options);
                System.exit(0);
            }
            if (cl.hasOption("u")) {
                parsedOptions.put(CommandLineOptions.USERNAME, cl.getOptionValue("u"));
            }
            if (cl.hasOption("p")) {
                parsedOptions.put(CommandLineOptions.PASSWORD, cl.getOptionValue("p"));
            }
            if (cl.hasOption("cs")) {
                parsedOptions.put(CommandLineOptions.CONNECT_STRING, cl.getOptionValue("cs"));
            }
            if (cl.hasOption("rc")) {
                parsedOptions.put(CommandLineOptions.ROWS_TO_INSERT, Long.parseLong(cl.getOptionValue("rc")));
            } else {
                parsedOptions.put(CommandLineOptions.ROWS_TO_INSERT, 100L);
            }
            if (cl.hasOption("cf")) {
                parsedOptions.put(CommandLineOptions.COMMIT_FREQUENCY, Long.parseLong(cl.getOptionValue("cf")));
            } else {
                parsedOptions.put(CommandLineOptions.COMMIT_FREQUENCY, 1L);
            }
            if (cl.hasOption("bs")) {
                parsedOptions.put(CommandLineOptions.BATCH_SIZE, Long.parseLong(cl.getOptionValue("bs")));
            } else {
                parsedOptions.put(CommandLineOptions.BATCH_SIZE, -1L);
            }
            if (cl.hasOption("tc")) {
                parsedOptions.put(CommandLineOptions.THREAD_COUNT, Integer.parseInt(cl.getOptionValue("tc")));
            } else {
                parsedOptions.put(CommandLineOptions.THREAD_COUNT, 1);
            }
            if (cl.hasOption("async")) {
                parsedOptions.put(CommandLineOptions.ASYNC, true);
            } else {
                parsedOptions.put(CommandLineOptions.ASYNC, false);
            }
            if (cl.hasOption("ops")) {
                parsedOptions.put(CommandLineOptions.OPERATIONS_TO_PERFORM, Long.parseLong(cl.getOptionValue("ops")));
            }
            if (cl.hasOption("dr")) {
                parsedOptions.put(CommandLineOptions.DATA_RANGE, Long.parseLong(cl.getOptionValue("dr")));
            }
            if (cl.hasOption("sql")) {
                parsedOptions.put(CommandLineOptions.SELECT_COMMAND, BenchmarkQuery.parseCLOption(cl.getOptionValue("sql")));
            } else {
                parsedOptions.put(CommandLineOptions.SELECT_COMMAND, BenchmarkQuery.SIMPLE_LOOKUP);
            }
            if (cl.hasOption("o")) {
                parsedOptions.put(CommandLineOptions.OUTPUT_RESULTS, OutputDestination.parseCLOption(cl.getOptionValue("o")));
            } else {
                parsedOptions.put(CommandLineOptions.OUTPUT_RESULTS, OutputDestination.STDOUT);
            }
            if (cl.hasOption("t")) {
                if (cl.getOptionValue("t").equals("postgresql")) {
                    parsedOptions.put(CommandLineOptions.TARGET_TYPE, DBType.POSTGRESQL);
                } else {
                    parsedOptions.put(CommandLineOptions.TARGET_TYPE, DBType.ORACLE);
                }
            } else {
                parsedOptions.put(CommandLineOptions.TARGET_TYPE, DBType.ORACLE);
            }
            if (cl.hasOption("st")) {
                TransactionType tt;
                try {
                    tt = TransactionType.getValue(cl.getOptionValue("st"));
                    parsedOptions.put(CommandLineOptions.TEST_TYPE, tt);
                } catch (IllegalArgumentException e) {
                    throw new ParseException(String.format("valid values for \"-st\" are %s",
                            Arrays.stream(TransactionType.values()).map(t -> t.toString().toLowerCase()).collect(Collectors.joining(", "))));
                }
            } else {
                parsedOptions.put(CommandLineOptions.TEST_TYPE, TransactionType.RELATIONAL);
            }
            if (cl.hasOption("sql")) {
                BenchmarkQuery bmq;
                try {
                    bmq = BenchmarkQuery.parseCLOption(cl.getOptionValue("sql"));
                    parsedOptions.put(CommandLineOptions.SELECT_COMMAND, bmq);
                } catch (RuntimeException re) {
                    throw new ParseException("valid values for select statement options are \" lookup,range_scan,count\"");
                }
            }
            parsedOptions.put(CommandLineOptions.BENCHMARK_TYPE, BenchmarkTask.parseCLOption(optionGroup.getSelected()));

        } catch (ParseException pe) {
            System.out.println("ERROR : " + pe.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("parameters:", options);
            System.exit(-1);
        }
        return parsedOptions;

    }

    private static long randomLong(long s, long e) {

        long result = 0;

        if ((e - s) > 0) {
            result = (Math.abs((new Random()).nextLong()) % (e - s)) + s;
        } else if ((e - s) == 0) {
            result = e;
        }

        return result;
    }

    public static LocalDate randomDate(LocalDate s, int days) {
        return (s.plusDays(randomInteger(0, days)));
    }

    public static int randomInteger(int s, int e) {

        if (s == e) {
            return s;
        } else {
            return ((e - s) <= 0) ? 0 : (((new Random()).nextInt(e - s)) + s);
        }
    }


    public static Date asDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant());
    }

    private static void printResults(ResultSet rs) {
        ResultSetMetaData resultSetMetaData = null;
        try {
            resultSetMetaData = rs.getMetaData();

            int columnCount = resultSetMetaData.getColumnCount();
            StringBuilder stringOutput = new StringBuilder("|");
            String tableformat = String.format("%1$-" + ((columnCount * 20) + columnCount + 1) + "s", " ");
            logger.fine(tableformat.replace(' ', '-'));
            for (int i = 1; i <= columnCount; i++) {
                String columnName = resultSetMetaData.getColumnName(i);
                stringOutput.append(String.format("%1$-20s|", columnName));
            }
            logger.fine(stringOutput.toString());
            if (rs.next()) {
                stringOutput = new StringBuilder("|");
                logger.fine(tableformat.replace(' ', '-'));
                for (int i = 1; i <= columnCount; i++) {
                    int type = resultSetMetaData.getColumnType(i);
                    if (type == Types.VARCHAR || type == Types.CHAR) {
                        String columnValue = rs.getString(i);
                        stringOutput.append(String.format("%1$-20s|", columnValue));
                    } else if (type == Types.NUMERIC) {
                        Long columnValue = rs.getLong(i);
                        stringOutput.append(String.format("%1$20d|", columnValue));
                    }
                }
                logger.fine(stringOutput.toString());

            }
            logger.fine(tableformat.replace(' ', '-'));
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }
}


