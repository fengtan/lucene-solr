package org.apache.solr.cloud;

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

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.lucene.util.LuceneTestCase.Nightly;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;

@Nightly
public class ConcurrentDeleteAndCreateCollectionTest extends SolrTestCaseJ4 {
  
  private MiniSolrCloudCluster solrCluster;
  
  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    final File solrXml = getFile("solr").toPath().resolve("solr.xml").toFile();
    solrCluster = new MiniSolrCloudCluster(1, createTempDir().toFile(), solrXml, buildJettyConfig("/solr"));
  }
  
  @Override
  @After
  public void tearDown() throws Exception {
    solrCluster.shutdown();
    super.tearDown();
  }
  
  public void testConcurrentCreateAndDeleteDoesNotFail() {
    final File configDir = getFile("solr").toPath().resolve("configsets/configset-2/conf").toFile();
    final AtomicReference<Exception> failure = new AtomicReference<>();
    final int timeToRunSec = 30;
    final Thread[] threads = new Thread[10];
    for (int i = 0; i < threads.length; i++) {
      final String collectionName = "collection" + i;
      uploadConfig(configDir, collectionName);
      final SolrClient solrClient = new HttpSolrClient(solrCluster.getJettySolrRunners().get(0).getBaseUrl().toString());
      threads[i] = new CreateDeleteSearchCollectionThread("create-delete-search-" + i, collectionName, collectionName, 
          timeToRunSec, solrClient, failure);
    }
    
    startAll(threads);
    joinAll(threads);
    
    assertNull("concurrent create and delete collection failed: " + failure.get(), failure.get());
  }
  
  public void testConcurrentCreateAndDeleteOverTheSameConfig() {
    // TODO: no idea what this test needs to override the level, but regardless of reason it should
    // reset when it's done.
    final Logger logger = Logger.getLogger("org.apache.solr");
    final Level SAVED_LEVEL = logger.getLevel();
    try {
      logger.setLevel(Level.WARN);
      final String configName = "testconfig";
      final File configDir = getFile("solr").toPath().resolve("configsets/configset-2/conf").toFile();
      uploadConfig(configDir, configName); // upload config once, to be used by all collections
      final SolrClient solrClient = new HttpSolrClient(solrCluster.getJettySolrRunners().get(0).getBaseUrl().toString());
      final AtomicReference<Exception> failure = new AtomicReference<>();
      final int timeToRunSec = 30;
      final Thread[] threads = new Thread[2];
      for (int i = 0; i < threads.length; i++) {
        final String collectionName = "collection" + i;
        threads[i] = new CreateDeleteCollectionThread("create-delete-" + i, collectionName, configName, 
                                                      timeToRunSec, solrClient, failure);
      }
    
      startAll(threads);
      joinAll(threads);
    
      assertNull("concurrent create and delete collection failed: " + failure.get(), failure.get());
      
      try {
        solrClient.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } finally {
      logger.setLevel(SAVED_LEVEL);
    }
  }
  
  private void uploadConfig(File configDir, String configName) {
    try {
      solrCluster.uploadConfigDir(configDir, configName);
    } catch (IOException | KeeperException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
  
  private void joinAll(final Thread[] threads) {
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.interrupted();
        throw new RuntimeException(e);
      }
    }
  }
  
  private void startAll(final Thread[] threads) {
    for (Thread t : threads) {
      t.start();
    }
  }
  
  private static class CreateDeleteCollectionThread extends Thread {
    protected final String collectionName;
    protected final String configName;
    protected final long timeToRunSec;
    protected final SolrClient solrClient;
    protected final AtomicReference<Exception> failure;
    
    public CreateDeleteCollectionThread(String name, String collectionName, String configName, long timeToRunSec,
        SolrClient solrClient, AtomicReference<Exception> failure) {
      super(name);
      this.collectionName = collectionName;
      this.timeToRunSec = timeToRunSec;
      this.solrClient = solrClient;
      this.failure = failure;
      this.configName = configName;
    }
    
    @Override
    public void run() {
      final long timeToStop = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeToRunSec);
      while (System.currentTimeMillis() < timeToStop && failure.get() == null) {
        doWork();
      }
    }
    
    protected void doWork() {
      createCollection();
      deleteCollection();
    }
    
    protected void addFailure(Exception e) {
      synchronized (failure) {
        if (failure.get() != null) {
          failure.get().addSuppressed(e);
        } else {
          failure.set(e);
        }
      }
    }
    
    private void createCollection() {
      try {
        final CollectionAdminResponse response = new CollectionAdminRequest.Create()
                .setCollectionName(collectionName)
                .setNumShards(1)
                .setReplicationFactor(1)
                .setConfigName(configName).process(solrClient);
        if (response.getStatus() != 0) {
          addFailure(new RuntimeException("failed to create collection " + collectionName));
        }
      } catch (Exception e) {
        addFailure(e);
      }
      
    }
    
    private void deleteCollection() {
      try {
        final CollectionAdminRequest.Delete deleteCollectionRequest = new CollectionAdminRequest.Delete()
                .setCollectionName(collectionName);
        
        final CollectionAdminResponse response = deleteCollectionRequest.process(solrClient);
        if (response.getStatus() != 0) {
          addFailure(new RuntimeException("failed to delete collection " + collectionName));
        }
      } catch (Exception e) {
        addFailure(e);
      }
    }
  }
  
  private static class CreateDeleteSearchCollectionThread extends CreateDeleteCollectionThread {

    public CreateDeleteSearchCollectionThread(String name, String collectionName, String configName, long timeToRunSec,
        SolrClient solrClient, AtomicReference<Exception> failure) {
      super(name, collectionName, configName, timeToRunSec, solrClient, failure);
    }
    
    @Override
    protected void doWork() {
      super.doWork();
      searchNonExistingCollection();
    }
    
    private void searchNonExistingCollection() {
      try {
        solrClient.query(collectionName, new SolrQuery("*"));
      } catch (Exception e) {
        if (!e.getMessage().contains("not found") && !e.getMessage().contains("Can not find")) {
          addFailure(e);
        }
      }
    }
    
  }
  
}
