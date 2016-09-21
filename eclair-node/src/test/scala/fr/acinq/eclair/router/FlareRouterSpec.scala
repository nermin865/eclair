package fr.acinq.eclair.router

import java.math.BigInteger

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Scripts
import fr.acinq.eclair.router.FlareRouter._
import lightning.neighbor_onion.Next.{Forward, Req}
import lightning._
import lightning.routing_table_update.update_type.OPEN
import org.jgrapht.graph.SimpleGraph
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, FunSuite, FunSuiteLike}
import org.scalatest.junit.JUnitRunner

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Created by PM on 09/09/2016.
  */
@RunWith(classOf[JUnitRunner])
class FlareRouterSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll {

  import FlareRouterSpec._

  test("add channel") {
    val myself = BinaryData("01")
    val graph = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    val channel = channel_desc(sha256_hash(1, 1, 2, 2), myself, BinaryData("02"))
    val updates = routing_table_update(channel, OPEN) :: Nil
    val (graph1, updates1) = include(myself, graph, updates, 3, Set())
    assert(graph1.containsVertex(channel.nodeA))
    assert(graph1.containsVertex(channel.nodeB))
    assert(graph1.containsEdge(NamedEdge(channel.channelId)))
    assert(updates1 == updates)
  }

  test("ignore channel > radius") {
    val myself = BinaryData("01")
    val graph = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    val channel1 = channel_desc(sha256_hash(1, 1, 2, 2), myself, BinaryData("02"))
    val channel2 = channel_desc(sha256_hash(2, 2, 3, 3), BinaryData("02"), BinaryData("03"))
    val channel3 = channel_desc(sha256_hash(3, 3, 4, 4), BinaryData("03"), BinaryData("04"))
    val updates = routing_table_update(channel1, OPEN) :: routing_table_update(channel2, OPEN) :: routing_table_update(channel3, OPEN) :: Nil
    val (graph1, updates1) = include(myself, graph, updates, 2, Set())
    assert(graph1.containsVertex(BinaryData("01")))
    assert(graph1.containsVertex(BinaryData("02")))
    assert(graph1.containsVertex(BinaryData("03")))
    assert(!graph1.containsVertex(BinaryData("04")))
    assert(updates1 == updates.dropRight(1))
  }

  test("build onion") {
    val msg = neighbor_onion(Req(beacon_req(BinaryData("01"))))
    val onion = buildOnion(BinaryData("03") :: BinaryData("04") :: Nil, msg)
    assert(onion == neighbor_onion(Forward(beacon_forward(BinaryData("03"), neighbor_onion(Forward(beacon_forward(BinaryData("04"), msg)))))))
  }

  test("graph clone") {
    val g1 = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    g1.addVertex(BinaryData("01"))
    g1.addVertex(BinaryData("02"))
    g1.addVertex(BinaryData("03"))
    g1.addEdge(BinaryData("01"), BinaryData("02"), NamedEdge("0102"))
    g1.addEdge(BinaryData("02"), BinaryData("03"), NamedEdge("0203"))

    val g2 = g1.clone()
    assert(g1 == g2)
  }

  test("include updates") {
    val nodeIds = (0 to 3).map(nodeId)
    val myself = nodeIds(0)
    val graph = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    /*
            0
           / \
          1   2
     */
    graph.addVertex(nodeIds(0))
    graph.addVertex(nodeIds(1))
    graph.addVertex(nodeIds(2))
    graph.addEdge(nodeIds(0), nodeIds(1), NamedEdge(channelId(nodeIds(0), nodeIds(1))))
    graph.addEdge(nodeIds(0), nodeIds(2), NamedEdge(channelId(nodeIds(0), nodeIds(2))))
    val updates = Seq(
      routing_table_update(channel_desc(channelId(nodeIds(3), nodeIds(1)), nodeIds(3), nodeIds(1)), OPEN),
      routing_table_update(channel_desc(channelId(nodeIds(1), nodeIds(0)), nodeIds(1), nodeIds(0)), OPEN)
    )
    val (graph1, _) = include(myself, graph, updates, 2, Set())
    assert(graph1.vertexSet().contains(nodeIds(3)))
  }

  test("basic routing and discovery") {
    val radius = 2
    val maxBeacons = 5
    val nodeIds = for (i <- 0 to 6) yield nodeId(i)
    val links = (0, 1) :: (0, 2) :: (1, 3) :: (1, 6) :: (2, 4) :: (4, 5) :: Nil
    val routers = nodeIds map (nodeId => system.actorOf(FlareRouter.props(nodeId, radius, maxBeacons), nodeId.toString().take(2)))

    def createChannel(a: Int, b: Int): Unit = {
      routers(a) ! ChannelOpened(channel_desc(channelId(nodeIds(a), nodeIds(b)), nodeIds(a), nodeIds(b)), system.actorSelection(routers(b).path))
      routers(b) ! ChannelOpened(channel_desc(channelId(nodeIds(a), nodeIds(b)), nodeIds(b), nodeIds(a)), system.actorSelection(routers(a).path))
    }

    links.foreach { case (a, b) => createChannel(a, b) }

    Thread.sleep(5000)

    implicit val timeout = Timeout(3 seconds)
    import system.dispatcher

    val futures = for (i <- 0 until nodeIds.length) yield (routers(i) ? 'info).mapTo[FlareInfo].map(info => i -> info)
    val future = Future.sequence(futures).map(_.toMap)
    val infos = Await.result(future, 3 seconds)
    assert(infos(0).neighbors === 2)
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}

object FlareRouterSpec {
  def channelId(a: BinaryData, b: BinaryData): BinaryData = {
    if (Scripts.isLess(a, b)) Crypto.sha256(a ++ b) else Crypto.sha256(b ++ a)
  }

  def nodeId(i: Int): BinaryData = {
    val a = BigInteger.valueOf(i).toByteArray
    (Array.fill[Byte](32 - a.length)(0) ++ a).reverse
  }
}