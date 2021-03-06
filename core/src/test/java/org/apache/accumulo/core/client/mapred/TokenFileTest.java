/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.core.client.mapred;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.impl.Credentials;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 */
public class TokenFileTest {
  private static AssertionError e1 = null;
  private static final String PREFIX = TokenFileTest.class.getSimpleName();
  private static final String INSTANCE_NAME = PREFIX + "_mapred_instance";
  private static final String TEST_TABLE_1 = PREFIX + "_mapred_table_1";
  private static final String TEST_TABLE_2 = PREFIX + "_mapred_table_2";

  private static class MRTokenFileTester extends Configured implements Tool {
    private static class TestMapper implements Mapper<Key,Value,Text,Mutation> {
      Key key = null;
      int count = 0;
      OutputCollector<Text,Mutation> finalOutput;

      @Override
      public void map(Key k, Value v, OutputCollector<Text,Mutation> output, Reporter reporter) throws IOException {
        finalOutput = output;
        try {
          if (key != null)
            assertEquals(key.getRow().toString(), new String(v.get()));
          assertEquals(k.getRow(), new Text(String.format("%09x", count + 1)));
          assertEquals(new String(v.get()), String.format("%09x", count));
        } catch (AssertionError e) {
          e1 = e;
        }
        key = new Key(k);
        count++;
      }

      @Override
      public void configure(JobConf job) {}

      @Override
      public void close() throws IOException {
        Mutation m = new Mutation("total");
        m.put("", "", Integer.toString(count));
        finalOutput.collect(new Text(), m);
      }

    }

    @Override
    public int run(String[] args) throws Exception {

      if (args.length != 4) {
        throw new IllegalArgumentException("Usage : " + MRTokenFileTester.class.getName() + " <user> <token file> <inputtable> <outputtable>");
      }

      String user = args[0];
      String tokenFile = args[1];
      String table1 = args[2];
      String table2 = args[3];

      JobConf job = new JobConf(getConf());
      job.setJarByClass(this.getClass());

      job.setInputFormat(AccumuloInputFormat.class);

      AccumuloInputFormat.setConnectorInfo(job, user, tokenFile);
      AccumuloInputFormat.setInputTableName(job, table1);
      AccumuloInputFormat.setMockInstance(job, INSTANCE_NAME);

      job.setMapperClass(TestMapper.class);
      job.setMapOutputKeyClass(Key.class);
      job.setMapOutputValueClass(Value.class);
      job.setOutputFormat(AccumuloOutputFormat.class);
      job.setOutputKeyClass(Text.class);
      job.setOutputValueClass(Mutation.class);

      AccumuloOutputFormat.setConnectorInfo(job, user, tokenFile);
      AccumuloOutputFormat.setCreateTables(job, false);
      AccumuloOutputFormat.setDefaultTableName(job, table2);
      AccumuloOutputFormat.setMockInstance(job, INSTANCE_NAME);

      job.setNumReduceTasks(0);

      return JobClient.runJob(job).isSuccessful() ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
      Configuration conf = CachedConfiguration.getInstance();
      conf.set("hadoop.tmp.dir", new File(args[1]).getParent());
      conf.set("mapreduce.cluster.local.dir", new File(System.getProperty("user.dir"), "target/mapreduce-tmp").getAbsolutePath());
      assertEquals(0, ToolRunner.run(conf, new MRTokenFileTester(), args));
    }
  }

  @Rule
  public TemporaryFolder folder = new TemporaryFolder(new File(System.getProperty("user.dir") + "/target"));

  @Test
  public void testMR() throws Exception {
    MockInstance mockInstance = new MockInstance(INSTANCE_NAME);
    Connector c = mockInstance.getConnector("root", new PasswordToken(""));
    c.tableOperations().create(TEST_TABLE_1);
    c.tableOperations().create(TEST_TABLE_2);
    BatchWriter bw = c.createBatchWriter(TEST_TABLE_1, new BatchWriterConfig());
    for (int i = 0; i < 100; i++) {
      Mutation m = new Mutation(new Text(String.format("%09x", i + 1)));
      m.put(new Text(), new Text(), new Value(String.format("%09x", i).getBytes()));
      bw.addMutation(m);
    }
    bw.close();

    File tf = folder.newFile("root_test.pw");
    PrintStream out = new PrintStream(tf);
    String outString = new Credentials("root", new PasswordToken("")).serialize();
    out.println(outString);
    out.close();

    MRTokenFileTester.main(new String[] {"root", tf.getAbsolutePath(), TEST_TABLE_1, TEST_TABLE_2});
    assertNull(e1);

    Scanner scanner = c.createScanner(TEST_TABLE_2, new Authorizations());
    Iterator<Entry<Key,Value>> iter = scanner.iterator();
    assertTrue(iter.hasNext());
    Entry<Key,Value> entry = iter.next();
    assertEquals(Integer.parseInt(new String(entry.getValue().get())), 100);
    assertFalse(iter.hasNext());
  }
}
