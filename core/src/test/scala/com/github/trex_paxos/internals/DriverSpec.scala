package com.github.trex_paxos.internals

import java.util.concurrent.TimeoutException
import akka.actor._
import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import com.github.trex_paxos.BaseDriver
import com.github.trex_paxos.library._
import org.scalatest.{Matchers, BeforeAndAfterAll, SpecLike}
import com.typesafe.config.ConfigFactory
import scala.compat.Platform
import scala.concurrent.duration._
import akka.util.Timeout

object DriverSpec {
  val conf = ConfigFactory.parseString("akka.loglevel = \"DEBUG\"\nakka.log-dead-letters-during-shutdown=false")
}

class DriverSpec extends TestKit(ActorSystem("DriverSpec",
  DriverSpec.conf)) with SpecLike with ImplicitSender with BeforeAndAfterAll with Matchers {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  import scala.language.postfixOps

  val timeout = Timeout(50 millisecond)

  class ForwardingActor(other: ActorRef) extends Actor with ActorLogging {
    log.debug("will forward from {} to {}", self.path, other)

    override def receive: Actor.Receive = {
      case msg =>
        log.debug("forwarding {} from {} to probe {}", msg, sender, other)
        other ! msg
    }
  }

  def clusterOf(a0: ActorRef, a1: ActorRef, a2: ActorRef)(implicit system: ActorSystem): Map[Int, ActorSelection] = {
    val now = Platform.currentTime
    system.actorOf(Props(new ForwardingActor(a0)), "a0-" + now)
    system.actorOf(Props(new ForwardingActor(a1)), "a1-" + now)
    system.actorOf(Props(new ForwardingActor(a2)), "a2-" + now)
    Map(0 -> system.actorSelection("/user/a0-" + now), 1 -> system.actorSelection("/user/a1-" + now), 2 -> system.actorSelection("/user/a2-" + now))
  }

  def fromBinary(bytes: Array[Byte])(implicit probe: TestActorRef[BaseDriver]) = probe.underlyingActor.getSerializer(classOf[String]).fromBinary(bytes)

  object `client driver` {

    def baseDriver = {
      val retries = 6
      val clientProbe = TestProbe()
      val testProbe1 = TestProbe()
      val testProbe2 = TestProbe()
      val testProbe3 = TestProbe()
      val cluster: Map[Int, ActorSelection] = clusterOf(testProbe1.ref, testProbe2.ref, testProbe3.ref)
      (TestActorRef(new BaseDriver(timeout, retries) {
        override def resolve(counter: Int): ActorSelection = cluster(counter % cluster.size)

        override def now() = Long.MaxValue
      }), clientProbe, testProbe1, testProbe2, testProbe3)
    }

    object `will return the response that comes from the first node` {
      val (bd, clientProbe, testProbe1, testProbe2, testProbe3) = baseDriver
      implicit val ref = bd
      clientProbe.send(ref, "hello")
      testProbe1.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(1, bytes) if fromBinary(bytes) == "hello" => // good
        case x => fail(x.toString)
      }
      testProbe1.send(ref, ServerResponse(1, Some("world")))
      clientProbe.expectMsgPF(1 seconds) {
        case "world" => // success
        case x => fail(x.toString)
      }
    }

    object `will return the an NoLongerLeaderException response that comes from the first node` {
      val (bd, clientProbe, testProbe1, testProbe2, testProbe3) = baseDriver
      implicit val ref = bd
      clientProbe.send(ref, "hello")
      testProbe1.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(1, bytes) if fromBinary(bytes) == "hello" => // good
        case x => fail(x.toString)
      }
      testProbe1.send(ref, new NoLongerLeaderException(2,1))
      clientProbe.expectMsgPF(1 seconds) {
        case nlle: NoLongerLeaderException if nlle.msgId == 1 && nlle.nodeId == 2=> // success
        case x => fail(x.toString)
      }
    }

    object `will return the response if it comes from the third node after timeouts` {
      val (bd, clientProbe, testProbe1, testProbe2, testProbe3) = baseDriver
      implicit val ref = bd

      clientProbe.send(ref, "hello")
      ref ! CheckTimeout
      ref ! CheckTimeout

      testProbe3.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(1, bytes) if fromBinary(bytes) == "hello" =>
        case x => fail(x.toString)
      }
      testProbe3.send(ref, ServerResponse(1, Some("world")))
      clientProbe.expectMsgPF(1 seconds) {
        case "world" => // success
        case x => fail(x.toString)
      }
    }

    object `will stick with the second node if that is what responds` {
      val (bd, clientProbe, testProbe1, testProbe2, testProbe3) = baseDriver
      implicit val ref = bd

      clientProbe.send(ref, "hello")
      testProbe1.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(1, bytes) if fromBinary(bytes) == "hello" =>
        case x => fail(x.toString)
      }
      ref ! CheckTimeout

      testProbe2.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(1, bytes) if fromBinary(bytes) == "hello" =>
        case x => fail(x.toString)
      }
      testProbe2.send(ref, ServerResponse(1, Some("world")))
      clientProbe.expectMsgPF(1 seconds) {
        case "world" => // success
        case x => fail(x.toString)
      }

      clientProbe.send(ref, "hello again")
      testProbe2.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(2, bytes) if fromBinary(bytes) == "hello again" =>
        case x => fail(x.toString)
      }
      testProbe2.send(ref, ServerResponse(2, Some("world again")))
      clientProbe.expectMsgPF(1 seconds) {
        case "world again" => // success
        case x => fail(x.toString)
      }

      testProbe1.expectNoMsg(25 millisecond)
      testProbe3.expectNoMsg(25 millisecond)
    }

    object `will return the response if it comes from the second node after a NotLeader message` {

      val (bd, clientProbe, testProbe1, testProbe2, testProbe3) = baseDriver
      implicit val ref = bd

      clientProbe.send(ref, "hello")

      testProbe1.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(1, bytes) if fromBinary(bytes) == "hello" =>
        case x => fail(x.toString)
      }
      testProbe1.send(ref, NotLeader(0, 1))
      testProbe2.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(1, bytes) if fromBinary(bytes) == "hello" =>
        case other => fail(s"got $other not bytes")
      }

      testProbe2.send(ref, ServerResponse(1, Some("world")))
      clientProbe.expectMsgPF(1 seconds) {
        case "world" => // success
        case x => fail(x.toString)
      }

      clientProbe.send(ref, "hello again")
      testProbe2.expectMsgPF(1 seconds) {
        case ClientRequestCommandValue(2, bytes) if fromBinary(bytes) == "hello again" =>
        case x => fail(x.toString)
      }
      testProbe2.send(ref, ServerResponse(2, Some("world again")))
      clientProbe.expectMsgPF(1 seconds) {
        case "world again" => // success
        case x => fail(x.toString)
      }

      testProbe1.expectNoMsg(25 millisecond)
      testProbe3.expectNoMsg(25 millisecond)

    }

    object `will give up after six attempts` {
      val (bd, clientProbe, testProbe1, testProbe2, testProbe3) = baseDriver
      implicit val ref = bd

      clientProbe.send(ref, "hello world")
      clientProbe.expectNoMsg(25 millisecond)

      ref ! CheckTimeout
      clientProbe.expectNoMsg(25 millisecond)
      ref ! CheckTimeout
      clientProbe.expectNoMsg(25 millisecond)
      ref ! CheckTimeout
      clientProbe.expectNoMsg(25 millisecond)
      ref ! CheckTimeout
      clientProbe.expectNoMsg(25 millisecond)
      ref ! CheckTimeout
      clientProbe.expectNoMsg(25 millisecond)
      ref ! CheckTimeout

      clientProbe.expectMsgPF(1 seconds) {
        case ex: TimeoutException =>
          ex.getMessage.indexOf(s"Exceeded maxAttempts 6") should be(0)
        case f => fail(f.toString)
      }

      Seq(testProbe1, testProbe2, testProbe3).foreach(_.receiveN(2).map({
        case ClientRequestCommandValue(_, bytes) => fromBinary(bytes)
        case f => fail(f.toString)
      }) should be(Seq("hello world", "hello world")))
    }

  }

}
