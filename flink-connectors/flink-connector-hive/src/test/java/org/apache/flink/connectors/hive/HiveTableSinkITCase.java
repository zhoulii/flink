/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connectors.hive;

import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.util.FiniteTestSource;
import org.apache.flink.table.api.ExplainDetail;
import org.apache.flink.table.api.SqlDialect;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.hive.HiveCatalog;
import org.apache.flink.table.catalog.hive.HiveTestUtils;
import org.apache.flink.types.Row;
import org.apache.flink.util.CollectionUtil;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.filesystem.FileSystemOptions.PARTITION_TIME_EXTRACTOR_TIMESTAMP_PATTERN;
import static org.apache.flink.table.filesystem.FileSystemOptions.SINK_PARTITION_COMMIT_DELAY;
import static org.apache.flink.table.filesystem.FileSystemOptions.SINK_PARTITION_COMMIT_POLICY_KIND;
import static org.apache.flink.table.filesystem.FileSystemOptions.SINK_PARTITION_COMMIT_SUCCESS_FILE_NAME;
import static org.apache.flink.table.planner.utils.TableTestUtil.readFromResource;
import static org.apache.flink.table.planner.utils.TableTestUtil.replaceStageId;
import static org.apache.flink.table.planner.utils.TableTestUtil.replaceStreamNodeId;
import static org.junit.Assert.assertEquals;

/** Tests {@link HiveTableSink}. */
public class HiveTableSinkITCase {

    private static HiveCatalog hiveCatalog;

    @BeforeClass
    public static void createCatalog() throws IOException {
        hiveCatalog = HiveTestUtils.createHiveCatalog();
        hiveCatalog.open();
    }

    @AfterClass
    public static void closeCatalog() {
        if (hiveCatalog != null) {
            hiveCatalog.close();
        }
    }

    @Test
    public void testHiveTableSinkWithParallelismInBatch() {
        final TableEnvironment tEnv =
                HiveTestUtils.createTableEnvWithBlinkPlannerBatchMode(SqlDialect.HIVE);
        testHiveTableSinkWithParallelismBase(
                tEnv, "/explain/testHiveTableSinkWithParallelismInBatch.out");
    }

    @Test
    public void testHiveTableSinkWithParallelismInStreaming() {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        final TableEnvironment tEnv =
                HiveTestUtils.createTableEnvWithBlinkPlannerStreamMode(env, SqlDialect.HIVE);
        testHiveTableSinkWithParallelismBase(
                tEnv, "/explain/testHiveTableSinkWithParallelismInStreaming.out");
    }

    private void testHiveTableSinkWithParallelismBase(
            final TableEnvironment tEnv, final String expectedResourceFileName) {
        tEnv.registerCatalog(hiveCatalog.getName(), hiveCatalog);
        tEnv.useCatalog(hiveCatalog.getName());
        tEnv.executeSql("create database db1");
        tEnv.useDatabase("db1");

        tEnv.executeSql(
                String.format(
                        "CREATE TABLE test_table ("
                                + " id int,"
                                + " real_col int"
                                + ") TBLPROPERTIES ("
                                + " 'sink.parallelism' = '8'" // set sink parallelism = 8
                                + ")"));
        final String actual =
                tEnv.explainSql(
                        "insert into test_table select 1, 1", ExplainDetail.JSON_EXECUTION_PLAN);
        final String expected = readFromResource(expectedResourceFileName);

        assertEquals(
                replaceStreamNodeId(replaceStageId(expected)),
                replaceStreamNodeId(replaceStageId(actual)));

        tEnv.executeSql("drop database db1 cascade");
    }

    @Test
    public void testBatchAppend() throws Exception {
        TableEnvironment tEnv =
                HiveTestUtils.createTableEnvWithBlinkPlannerBatchMode(SqlDialect.HIVE);
        tEnv.registerCatalog(hiveCatalog.getName(), hiveCatalog);
        tEnv.useCatalog(hiveCatalog.getName());
        tEnv.executeSql("create database db1");
        tEnv.useDatabase("db1");
        try {
            tEnv.executeSql("create table append_table (i int, j int)");
            tEnv.executeSql("insert into append_table select 1, 1").await();
            tEnv.executeSql("insert into append_table select 2, 2").await();
            List<Row> rows =
                    CollectionUtil.iteratorToList(
                            tEnv.executeSql("select * from append_table").collect());
            rows.sort(Comparator.comparingInt(o -> (int) o.getField(0)));
            Assert.assertEquals(Arrays.asList(Row.of(1, 1), Row.of(2, 2)), rows);
        } finally {
            tEnv.executeSql("drop database db1 cascade");
        }
    }

    @Test(timeout = 120000)
    public void testDefaultSerPartStreamingWrite() throws Exception {
        testStreamingWrite(true, false, "textfile", this::checkSuccessFiles);
    }

    @Test(timeout = 120000)
    public void testPartStreamingWrite() throws Exception {
        testStreamingWrite(true, false, "parquet", this::checkSuccessFiles);
        // disable vector orc writer test for hive 2.x due to dependency conflict
        if (!hiveCatalog.getHiveVersion().startsWith("2.")) {
            testStreamingWrite(true, false, "orc", this::checkSuccessFiles);
        }
    }

    @Test(timeout = 120000)
    public void testNonPartStreamingWrite() throws Exception {
        testStreamingWrite(false, false, "parquet", (p) -> {});
        // disable vector orc writer test for hive 2.x due to dependency conflict
        if (!hiveCatalog.getHiveVersion().startsWith("2.")) {
            testStreamingWrite(false, false, "orc", (p) -> {});
        }
    }

    @Test(timeout = 120000)
    public void testPartStreamingMrWrite() throws Exception {
        testStreamingWrite(true, true, "parquet", this::checkSuccessFiles);
        // doesn't support writer 2.0 orc table
        if (!hiveCatalog.getHiveVersion().startsWith("2.0")) {
            testStreamingWrite(true, true, "orc", this::checkSuccessFiles);
        }
    }

    @Test(timeout = 120000)
    public void testNonPartStreamingMrWrite() throws Exception {
        testStreamingWrite(false, true, "parquet", (p) -> {});
        // doesn't support writer 2.0 orc table
        if (!hiveCatalog.getHiveVersion().startsWith("2.0")) {
            testStreamingWrite(false, true, "orc", (p) -> {});
        }
    }

    @Test(timeout = 120000)
    public void testStreamingAppend() throws Exception {
        testStreamingWrite(
                false,
                false,
                "parquet",
                (p) -> {
                    StreamExecutionEnvironment env =
                            StreamExecutionEnvironment.getExecutionEnvironment();
                    env.setParallelism(1);
                    StreamTableEnvironment tEnv =
                            HiveTestUtils.createTableEnvWithBlinkPlannerStreamMode(env);
                    tEnv.registerCatalog(hiveCatalog.getName(), hiveCatalog);
                    tEnv.useCatalog(hiveCatalog.getName());

                    try {
                        tEnv.executeSql(
                                        "insert into db1.sink_table select 6,'a','b','2020-05-03','12'")
                                .await();
                    } catch (Exception e) {
                        Assert.fail("Failed to execute sql: " + e.getMessage());
                    }

                    assertBatch(
                            "db1.sink_table",
                            Arrays.asList(
                                    "+I[1, a, b, 2020-05-03, 7]",
                                    "+I[1, a, b, 2020-05-03, 7]",
                                    "+I[2, p, q, 2020-05-03, 8]",
                                    "+I[2, p, q, 2020-05-03, 8]",
                                    "+I[3, x, y, 2020-05-03, 9]",
                                    "+I[3, x, y, 2020-05-03, 9]",
                                    "+I[4, x, y, 2020-05-03, 10]",
                                    "+I[4, x, y, 2020-05-03, 10]",
                                    "+I[5, x, y, 2020-05-03, 11]",
                                    "+I[5, x, y, 2020-05-03, 11]",
                                    "+I[6, a, b, 2020-05-03, 12]"));
                });
    }

    private void checkSuccessFiles(String path) {
        File basePath = new File(path, "d=2020-05-03");
        Assert.assertEquals(5, basePath.list().length);
        Assert.assertTrue(new File(new File(basePath, "e=7"), "_MY_SUCCESS").exists());
        Assert.assertTrue(new File(new File(basePath, "e=8"), "_MY_SUCCESS").exists());
        Assert.assertTrue(new File(new File(basePath, "e=9"), "_MY_SUCCESS").exists());
        Assert.assertTrue(new File(new File(basePath, "e=10"), "_MY_SUCCESS").exists());
        Assert.assertTrue(new File(new File(basePath, "e=11"), "_MY_SUCCESS").exists());
    }

    private void testStreamingWrite(
            boolean part, boolean useMr, String format, Consumer<String> pathConsumer)
            throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.enableCheckpointing(100);

        StreamTableEnvironment tEnv = HiveTestUtils.createTableEnvWithBlinkPlannerStreamMode(env);
        tEnv.registerCatalog(hiveCatalog.getName(), hiveCatalog);
        tEnv.useCatalog(hiveCatalog.getName());
        tEnv.getConfig().setSqlDialect(SqlDialect.HIVE);
        if (useMr) {
            tEnv.getConfig()
                    .getConfiguration()
                    .set(HiveOptions.TABLE_EXEC_HIVE_FALLBACK_MAPRED_WRITER, true);
        } else {
            tEnv.getConfig()
                    .getConfiguration()
                    .set(HiveOptions.TABLE_EXEC_HIVE_FALLBACK_MAPRED_WRITER, false);
        }

        try {
            tEnv.executeSql("create database db1");
            tEnv.useDatabase("db1");

            // prepare source
            List<Row> data =
                    Arrays.asList(
                            Row.of(1, "a", "b", "2020-05-03", "7"),
                            Row.of(2, "p", "q", "2020-05-03", "8"),
                            Row.of(3, "x", "y", "2020-05-03", "9"),
                            Row.of(4, "x", "y", "2020-05-03", "10"),
                            Row.of(5, "x", "y", "2020-05-03", "11"));
            DataStream<Row> stream =
                    env.addSource(
                            new FiniteTestSource<>(data),
                            new RowTypeInfo(
                                    Types.INT,
                                    Types.STRING,
                                    Types.STRING,
                                    Types.STRING,
                                    Types.STRING));
            tEnv.createTemporaryView("my_table", stream, $("a"), $("b"), $("c"), $("d"), $("e"));

            // DDL
            tEnv.executeSql(
                    "create external table sink_table (a int,b string,c string"
                            + (part ? "" : ",d string,e string")
                            + ") "
                            + (part ? "partitioned by (d string,e string) " : "")
                            + " stored as "
                            + format
                            + " TBLPROPERTIES ("
                            + "'"
                            + PARTITION_TIME_EXTRACTOR_TIMESTAMP_PATTERN.key()
                            + "'='$d $e:00:00',"
                            + "'"
                            + SINK_PARTITION_COMMIT_DELAY.key()
                            + "'='1h',"
                            + "'"
                            + SINK_PARTITION_COMMIT_POLICY_KIND.key()
                            + "'='metastore,success-file',"
                            + "'"
                            + SINK_PARTITION_COMMIT_SUCCESS_FILE_NAME.key()
                            + "'='_MY_SUCCESS'"
                            + ")");

            tEnv.sqlQuery("select * from my_table").executeInsert("sink_table").await();

            assertBatch(
                    "db1.sink_table",
                    Arrays.asList(
                            "+I[1, a, b, 2020-05-03, 7]",
                            "+I[1, a, b, 2020-05-03, 7]",
                            "+I[2, p, q, 2020-05-03, 8]",
                            "+I[2, p, q, 2020-05-03, 8]",
                            "+I[3, x, y, 2020-05-03, 9]",
                            "+I[3, x, y, 2020-05-03, 9]",
                            "+I[4, x, y, 2020-05-03, 10]",
                            "+I[4, x, y, 2020-05-03, 10]",
                            "+I[5, x, y, 2020-05-03, 11]",
                            "+I[5, x, y, 2020-05-03, 11]"));

            // using batch table env to query.
            List<String> results = new ArrayList<>();
            TableEnvironment batchTEnv = HiveTestUtils.createTableEnvWithBlinkPlannerBatchMode();
            batchTEnv.registerCatalog(hiveCatalog.getName(), hiveCatalog);
            batchTEnv.useCatalog(hiveCatalog.getName());
            batchTEnv
                    .executeSql("select * from db1.sink_table")
                    .collect()
                    .forEachRemaining(r -> results.add(r.toString()));
            results.sort(String::compareTo);
            Assert.assertEquals(
                    Arrays.asList(
                            "+I[1, a, b, 2020-05-03, 7]",
                            "+I[1, a, b, 2020-05-03, 7]",
                            "+I[2, p, q, 2020-05-03, 8]",
                            "+I[2, p, q, 2020-05-03, 8]",
                            "+I[3, x, y, 2020-05-03, 9]",
                            "+I[3, x, y, 2020-05-03, 9]",
                            "+I[4, x, y, 2020-05-03, 10]",
                            "+I[4, x, y, 2020-05-03, 10]",
                            "+I[5, x, y, 2020-05-03, 11]",
                            "+I[5, x, y, 2020-05-03, 11]"),
                    results);

            pathConsumer.accept(
                    URI.create(
                                    hiveCatalog
                                            .getHiveTable(ObjectPath.fromString("db1.sink_table"))
                                            .getSd()
                                            .getLocation())
                            .getPath());
        } finally {
            tEnv.executeSql("drop database db1 cascade");
        }
    }

    private void assertBatch(String table, List<String> expected) {
        // using batch table env to query.
        List<String> results = new ArrayList<>();
        TableEnvironment batchTEnv = HiveTestUtils.createTableEnvWithBlinkPlannerBatchMode();
        batchTEnv.registerCatalog(hiveCatalog.getName(), hiveCatalog);
        batchTEnv.useCatalog(hiveCatalog.getName());
        batchTEnv
                .executeSql("select * from " + table)
                .collect()
                .forEachRemaining(r -> results.add(r.toString()));
        results.sort(String::compareTo);
        expected.sort(String::compareTo);
        Assert.assertEquals(expected, results);
    }
}
