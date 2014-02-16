package org.apache.helix.store.zk;

import java.util.Date;

import org.I0Itec.zkclient.DataUpdater;
import org.apache.helix.AccessOption;
import org.apache.helix.BaseDataAccessor;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.TestHelper;
import org.apache.helix.ZNRecord;
import org.apache.helix.ZkUnitTestBase;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.manager.zk.ZkBaseDataAccessor;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TestZkManagerWithAutoFallbackStore extends ZkUnitTestBase {
  @Test
  public void testBasic() throws Exception {
    // Logger.getRootLogger().setLevel(Level.INFO);
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;
    int n = 2;

    System.out.println("START " + clusterName + " at " + new Date(System.currentTimeMillis()));

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
        "localhost", // participant name prefix
        "TestDB", // resource name prefix
        1, // resources
        32, // partitions per resource
        n, // number of nodes
        2, // replicas
        "MasterSlave", false); // do rebalance

    // start participants
    MockParticipantManager[] participants = new MockParticipantManager[n];
    for (int i = 0; i < 1; i++) {
      String instanceName = "localhost_" + (12918 + i);

      participants[i] = new MockParticipantManager(ZK_ADDR, clusterName, instanceName);
      participants[i].syncStart();
    }

    // add some data to fallback path: HELIX_PROPERTYSTORE
    BaseDataAccessor<ZNRecord> accessor =
        participants[0].getHelixDataAccessor().getBaseDataAccessor();
    for (int i = 0; i < 10; i++) {
      String path = String.format("/%s/HELIX_PROPERTYSTORE/%d", clusterName, i);
      ZNRecord record = new ZNRecord("" + i);
      record.setSimpleField("key1", "value1");
      accessor.set(path, record, AccessOption.PERSISTENT);
    }

    ZkHelixPropertyStore<ZNRecord> store = participants[0].getHelixPropertyStore();

    // read shall use fallback paths
    for (int i = 0; i < 10; i++) {
      String path = String.format("/%d", i);
      ZNRecord record = store.get(path, null, 0);
      Assert.assertNotNull(record);
      Assert.assertEquals(record.getId(), "" + i);
      Assert.assertNotNull(record.getSimpleField("key1"));
      Assert.assertEquals(record.getSimpleField("key1"), "value1");
    }

    // update shall update new paths
    for (int i = 0; i < 10; i++) {
      String path = String.format("/%d", i);
      store.update(path, new DataUpdater<ZNRecord>() {

        @Override
        public ZNRecord update(ZNRecord currentData) {
          if (currentData != null) {
            currentData.setSimpleField("key2", "value2");
          }
          return currentData;
        }
      }, AccessOption.PERSISTENT);
    }

    for (int i = 0; i < 10; i++) {
      String path = String.format("/%d", i);
      ZNRecord record = store.get(path, null, 0);
      Assert.assertNotNull(record);
      Assert.assertEquals(record.getId(), "" + i);
      Assert.assertNotNull(record.getSimpleField("key1"));
      Assert.assertEquals(record.getSimpleField("key1"), "value1");
      Assert.assertNotNull(record.getSimpleField("key2"));
      Assert.assertEquals(record.getSimpleField("key2"), "value2");
    }

    // set shall use new path
    for (int i = 10; i < 20; i++) {
      String path = String.format("/%d", i);
      ZNRecord record = new ZNRecord("" + i);
      record.setSimpleField("key3", "value3");
      store.set(path, record, AccessOption.PERSISTENT);
    }

    for (int i = 10; i < 20; i++) {
      String path = String.format("/%d", i);
      ZNRecord record = store.get(path, null, 0);
      Assert.assertNotNull(record);
      Assert.assertEquals(record.getId(), "" + i);
      Assert.assertNotNull(record.getSimpleField("key3"));
      Assert.assertEquals(record.getSimpleField("key3"), "value3");
    }

    participants[0].syncStop();
    System.out.println("END " + clusterName + " at " + new Date(System.currentTimeMillis()));
  }

}
