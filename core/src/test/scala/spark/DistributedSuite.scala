package spark

import network.ConnectionManagerId
import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.prop.Checkers
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalacheck.Prop._

import com.google.common.io.Files

import scala.collection.mutable.ArrayBuffer

import SparkContext._
import storage.{GetBlock, BlockManagerWorker, StorageLevel}

class NotSerializableClass
class NotSerializableExn(val notSer: NotSerializableClass) extends Throwable() {}

class DistributedSuite extends FunSuite with ShouldMatchers with BeforeAndAfter with LocalSparkContext {

  val clusterUrl = "local-cluster[2,1,512]"

  after {
    System.clearProperty("spark.reducer.maxMbInFlight")
    System.clearProperty("spark.storage.memoryFraction")
  }

  test("task throws not serializable exception") {
    // Ensures that executors do not crash when an exn is not serializable. If executors crash,
    // this test will hang. Correct behavior is that executors don't crash but fail tasks
    // and the scheduler throws a SparkException.

    // numSlaves must be less than numPartitions
    val numSlaves = 3
    val numPartitions = 10

    sc = new SparkContext("local-cluster[%s,1,512]".format(numSlaves), "test")
    val data = sc.parallelize(1 to 100, numPartitions).
      map(x => throw new NotSerializableExn(new NotSerializableClass))
    intercept[SparkException] {
      data.count()
    }
    resetSparkContext()
  }

  test("local-cluster format") {
    sc = new SparkContext("local-cluster[2,1,512]", "test")
    assert(sc.parallelize(1 to 2, 2).count() == 2)
    resetSparkContext()
    sc = new SparkContext("local-cluster[2 , 1 , 512]", "test")
    assert(sc.parallelize(1 to 2, 2).count() == 2)
    resetSparkContext()
    sc = new SparkContext("local-cluster[2, 1, 512]", "test")
    assert(sc.parallelize(1 to 2, 2).count() == 2)
    resetSparkContext()
    sc = new SparkContext("local-cluster[ 2, 1, 512 ]", "test")
    assert(sc.parallelize(1 to 2, 2).count() == 2)
    resetSparkContext()
  }

  test("simple groupByKey") {
    sc = new SparkContext(clusterUrl, "test")
    val pairs = sc.parallelize(Array((1, 1), (1, 2), (1, 3), (2, 1)), 5)
    val groups = pairs.groupByKey(5).collect()
    assert(groups.size === 2)
    val valuesFor1 = groups.find(_._1 == 1).get._2
    assert(valuesFor1.toList.sorted === List(1, 2, 3))
    val valuesFor2 = groups.find(_._1 == 2).get._2
    assert(valuesFor2.toList.sorted === List(1))
  }

  test("groupByKey where map output sizes exceed maxMbInFlight") {
    System.setProperty("spark.reducer.maxMbInFlight", "1")
    sc = new SparkContext(clusterUrl, "test")
    // This data should be around 20 MB, so even with 4 mappers and 2 reducers, each map output
    // file should be about 2.5 MB
    val pairs = sc.parallelize(1 to 2000, 4).map(x => (x % 16, new Array[Byte](10000)))
    val groups = pairs.groupByKey(2).map(x => (x._1, x._2.size)).collect()
    assert(groups.length === 16)
    assert(groups.map(_._2).sum === 2000)
    // Note that spark.reducer.maxMbInFlight will be cleared in the test suite's after{} block
  }

  test("accumulators") {
    sc = new SparkContext(clusterUrl, "test")
    val accum = sc.accumulator(0)
    sc.parallelize(1 to 10, 10).foreach(x => accum += x)
    assert(accum.value === 55)
  }

  test("broadcast variables") {
    sc = new SparkContext(clusterUrl, "test")
    val array = new Array[Int](100)
    val bv = sc.broadcast(array)
    array(2) = 3     // Change the array -- this should not be seen on workers
    val rdd = sc.parallelize(1 to 10, 10)
    val sum = rdd.map(x => bv.value.sum).reduce(_ + _)
    assert(sum === 0)
  }

  test("repeatedly failing task") {
    sc = new SparkContext(clusterUrl, "test")
    val accum = sc.accumulator(0)
    val thrown = intercept[SparkException] {
      sc.parallelize(1 to 10, 10).foreach(x => println(x / 0))
    }
    assert(thrown.getClass === classOf[SparkException])
    assert(thrown.getMessage.contains("more than 4 times"))
  }

  test("caching") {
    sc = new SparkContext(clusterUrl, "test")
    val data = sc.parallelize(1 to 1000, 10).cache()
    assert(data.count() === 1000)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
  }

  test("caching on disk") {
    sc = new SparkContext(clusterUrl, "test")
    val data = sc.parallelize(1 to 1000, 10).persist(StorageLevel.DISK_ONLY)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
  }

  test("caching in memory, replicated") {
    sc = new SparkContext(clusterUrl, "test")
    val data = sc.parallelize(1 to 1000, 10).persist(StorageLevel.MEMORY_ONLY_2)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
  }

  test("caching in memory, serialized, replicated") {
    sc = new SparkContext(clusterUrl, "test")
    val data = sc.parallelize(1 to 1000, 10).persist(StorageLevel.MEMORY_ONLY_SER_2)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
  }

  test("caching on disk, replicated") {
    sc = new SparkContext(clusterUrl, "test")
    val data = sc.parallelize(1 to 1000, 10).persist(StorageLevel.DISK_ONLY_2)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
  }

  test("caching in memory and disk, replicated") {
    sc = new SparkContext(clusterUrl, "test")
    val data = sc.parallelize(1 to 1000, 10).persist(StorageLevel.MEMORY_AND_DISK_2)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
    assert(data.count() === 1000)
  }

  test("caching in memory and disk, serialized, replicated") {
    sc = new SparkContext(clusterUrl, "test")
    val data = sc.parallelize(1 to 1000, 10).persist(StorageLevel.MEMORY_AND_DISK_SER_2)

    assert(data.count() === 1000)
    assert(data.count() === 1000)
    assert(data.count() === 1000)

    // Get all the locations of the first partition and try to fetch the partitions
    // from those locations.
    val blockIds = data.partitions.indices.map(index => "rdd_%d_%d".format(data.id, index)).toArray
    val blockId = blockIds(0)
    val blockManager = SparkEnv.get.blockManager
    blockManager.master.getLocations(blockId).foreach(id => {
      val bytes = BlockManagerWorker.syncGetBlock(
        GetBlock(blockId), ConnectionManagerId(id.ip, id.port))
      val deserialized = blockManager.dataDeserialize(blockId, bytes).asInstanceOf[Iterator[Int]].toList
      assert(deserialized === (1 to 100).toList)
    })
  }

  test("compute without caching when no partitions fit in memory") {
    System.setProperty("spark.storage.memoryFraction", "0.0001")
    sc = new SparkContext(clusterUrl, "test")
    // data will be 4 million * 4 bytes = 16 MB in size, but our memoryFraction set the cache
    // to only 50 KB (0.0001 of 512 MB), so no partitions should fit in memory
    val data = sc.parallelize(1 to 4000000, 2).persist(StorageLevel.MEMORY_ONLY_SER)
    assert(data.count() === 4000000)
    assert(data.count() === 4000000)
    assert(data.count() === 4000000)
    System.clearProperty("spark.storage.memoryFraction")
  }

  test("compute when only some partitions fit in memory") {
    System.setProperty("spark.storage.memoryFraction", "0.01")
    sc = new SparkContext(clusterUrl, "test")
    // data will be 4 million * 4 bytes = 16 MB in size, but our memoryFraction set the cache
    // to only 5 MB (0.01 of 512 MB), so not all of it will fit in memory; we use 20 partitions
    // to make sure that *some* of them do fit though
    val data = sc.parallelize(1 to 4000000, 20).persist(StorageLevel.MEMORY_ONLY_SER)
    assert(data.count() === 4000000)
    assert(data.count() === 4000000)
    assert(data.count() === 4000000)
    System.clearProperty("spark.storage.memoryFraction")
  }

  test("passing environment variables to cluster") {
    sc = new SparkContext(clusterUrl, "test", null, Nil, Map("TEST_VAR" -> "TEST_VALUE"))
    val values = sc.parallelize(1 to 2, 2).map(x => System.getenv("TEST_VAR")).collect()
    assert(values.toSeq === Seq("TEST_VALUE", "TEST_VALUE"))
  }

  test("recover from node failures") {
    import DistributedSuite.{markNodeIfIdentity, failOnMarkedIdentity}
    DistributedSuite.amMaster = true
    sc = new SparkContext(clusterUrl, "test")
    val data = sc.parallelize(Seq(true, true), 2)
    assert(data.count === 2) // force executors to start
    val masterId = SparkEnv.get.blockManager.blockManagerId
    assert(data.map(markNodeIfIdentity).collect.size === 2)
    assert(data.map(failOnMarkedIdentity).collect.size === 2)
  }

  test("recover from repeated node failures during shuffle-map") {
    import DistributedSuite.{markNodeIfIdentity, failOnMarkedIdentity}
    DistributedSuite.amMaster = true
    sc = new SparkContext(clusterUrl, "test")
    for (i <- 1 to 3) {
      val data = sc.parallelize(Seq(true, false), 2)
      assert(data.count === 2)
      assert(data.map(markNodeIfIdentity).collect.size === 2)
      assert(data.map(failOnMarkedIdentity).map(x => x -> x).groupByKey.count === 2)
    }
  }

  test("recover from repeated node failures during shuffle-reduce") {
    import DistributedSuite.{markNodeIfIdentity, failOnMarkedIdentity}
    DistributedSuite.amMaster = true
    sc = new SparkContext(clusterUrl, "test")
    for (i <- 1 to 3) {
      val data = sc.parallelize(Seq(true, true), 2)
      assert(data.count === 2)
      assert(data.map(markNodeIfIdentity).collect.size === 2)
      // This relies on mergeCombiners being used to perform the actual reduce for this
      // test to actually be testing what it claims.
      val grouped = data.map(x => x -> x).combineByKey(
                      x => x,
                      (x: Boolean, y: Boolean) => x,
                      (x: Boolean, y: Boolean) => failOnMarkedIdentity(x)
                    )
      assert(grouped.collect.size === 1)
    }
  }

  test("recover from node failures with replication") {
    import DistributedSuite.{markNodeIfIdentity, failOnMarkedIdentity}
    DistributedSuite.amMaster = true
    // Using more than two nodes so we don't have a symmetric communication pattern and might
    // cache a partially correct list of peers.
    sc = new SparkContext("local-cluster[3,1,512]", "test")
    for (i <- 1 to 3) {
      val data = sc.parallelize(Seq(true, false, false, false), 4)
      data.persist(StorageLevel.MEMORY_ONLY_2)

      assert(data.count === 4)
      assert(data.map(markNodeIfIdentity).collect.size === 4)
      assert(data.map(failOnMarkedIdentity).collect.size === 4)

      // Create a new replicated RDD to make sure that cached peer information doesn't cause
      // problems.
      val data2 = sc.parallelize(Seq(true, true), 2).persist(StorageLevel.MEMORY_ONLY_2)
      assert(data2.count === 2)
    }
  }

  test("job should fail if TaskResult exceeds Akka frame size") {
    // We must use local-cluster mode since results are returned differently
    // when running under LocalScheduler:
    sc = new SparkContext("local-cluster[1,1,512]", "test")
    val akkaFrameSize =
      sc.env.actorSystem.settings.config.getBytes("akka.remote.netty.message-frame-size").toInt
    val rdd = sc.parallelize(Seq(1)).map{x => new Array[Byte](akkaFrameSize)}
    val exception = intercept[SparkException] {
      rdd.reduce((x, y) => x)
    }
    exception.getMessage should endWith("result exceeded Akka frame size")
  }
}

object DistributedSuite {
  // Indicates whether this JVM is marked for failure.
  var mark = false
  
  // Set by test to remember if we are in the driver program so we can assert
  // that we are not.
  var amMaster = false

  // Act like an identity function, but if the argument is true, set mark to true.
  def markNodeIfIdentity(item: Boolean): Boolean = {
    if (item) {
      assert(!amMaster)
      mark = true
    }
    item
  }

  // Act like an identity function, but if mark was set to true previously, fail,
  // crashing the entire JVM.
  def failOnMarkedIdentity(item: Boolean): Boolean = {
    if (mark) { 
      System.exit(42)
    } 
    item
  } 
}
