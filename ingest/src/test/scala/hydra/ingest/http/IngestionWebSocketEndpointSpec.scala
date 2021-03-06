package hydra.ingest.http

import akka.actor.Actor
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.testkit.{TestActorRef, TestKit}
import hydra.core.protocol._
import hydra.ingest.IngestorInfo
import hydra.ingest.services.IngestorRegistry.{FindAll, FindByName, LookupResult}
import hydra.ingest.test.TestRecordFactory
import org.joda.time.DateTime
import org.scalatest.{Matchers, WordSpecLike}
import akka.pattern.pipe
import scala.concurrent.duration._

/**
  * Created by alexsilva on 5/12/17.
  */
class IngestionWebSocketEndpointSpec extends Matchers with WordSpecLike with ScalatestRouteTest {

  val endpt = new IngestionWebSocketEndpoint()

  override def afterAll = {
    super.afterAll()
    TestKit.shutdownActorSystem(system, verifySystemShutdown = true, duration = 10 seconds)
  }

  val ingestor = TestActorRef(new Actor {
    override def receive = {
      case Publish(_) => sender ! Join
      case Validate(r) =>
        TestRecordFactory.build(r).map(ValidRequest(_)) pipeTo sender
      case Ingest(req, _) if req.payload == "error" => sender ! IngestorError(new IllegalArgumentException)
      case Ingest(_, _) => sender ! IngestorCompleted
    }
  }, "test_ingestor")


  val ingestorInfo = IngestorInfo("test_ingestor", "test", ingestor.path, DateTime.now)

  val registry = TestActorRef(new Actor {
    override def receive = {
      case FindByName("tester") => sender ! LookupResult(Seq(ingestorInfo))
      case FindAll => sender ! LookupResult(Seq(ingestorInfo))
    }
  }, "ingestor_registry")


  "the IngestionWebSocketEndpoint" should {

    "returned a 409 if not enabled" in {
      val endpt = new IngestionWebSocketEndpoint() {
        override val enabled = false
      }
      val wsClient = WSProbe()
      WS("/ws-ingest", wsClient.flow) ~> endpt.route ~> check {
        response.status.intValue() shouldBe 409
      }

    }

    "handle websocket requests" in {
      val wsClient = WSProbe()

      WS("/ws-ingest", wsClient.flow) ~> endpt.route ~> check {
        // check response for WS Upgrade headers
        isWebSocketUpgrade shouldEqual true

        wsClient.sendMessage("-c SET hydra-kafka-topic = test.Topic")
        wsClient.expectMessage("""{"status":200,"message":"OK[HYDRA-KAFKA-TOPIC=test.Topic]"}""")
        wsClient.sendMessage("-c SET hydra-ack = explicit")
        wsClient.expectMessage("""{"status":200,"message":"OK[HYDRA-ACK=explicit]"}""")

        wsClient.sendMessage("-c WHAT")
        wsClient.expectMessage("""{"status":400,"message":"BAD_REQUEST:Not a valid message. Use 'HELP' for help."}""")

        wsClient.sendMessage("-c SET")
        wsClient.expectMessage("""{"status":200,"message":"HYDRA-KAFKA-TOPIC -> test.Topic;HYDRA-ACK -> explicit"}""")

        wsClient.sendMessage("-c HELP")
        wsClient.expectMessage("""{"status":200,"message":"Set metadata: --set (name)=(value)"}""")


        wsClient.sendMessage("""{"name":"test","value":"test"}""")
        wsClient.expectMessage("""{"correlationId":"0","ingestors":{"test_ingestor":{"code":200,"message":"OK"}}}""")

        wsClient.sendMessage("""-i 122 {"name":"test","value":"test"}""")
        wsClient.expectMessage("""{"correlationId":"122","ingestors":{"test_ingestor":{"code":200,"message":"OK"}}}""")

        wsClient.sendMessage("error")
        wsClient.expectMessage("""{"correlationId":"0","ingestors":{"test_ingestor":{"code":503,"message":"Unknown error."}}}""")

        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }

    }
    "sets metadata" in {
      val wsClient = WSProbe()

      WS("/ws-ingest", wsClient.flow) ~> endpt.route ~> check {
        // check response for WS Upgrade headers
        isWebSocketUpgrade shouldEqual true

        wsClient.sendMessage("-c SET hydra-delivery-strategy = at-most-once")
        wsClient.expectMessage("""{"status":200,"message":"OK[HYDRA-DELIVERY-STRATEGY=at-most-once]"}""")
        wsClient.sendMessage("-c SET hydra-client-id = test-client")
        wsClient.expectMessage("""{"status":200,"message":"OK[HYDRA-CLIENT-ID=test-client]"}""")

        wsClient.sendMessage("""-i 122 {"name":"test","value":"test"}""")
        wsClient.expectMessage("""{"correlationId":"122","ingestors":{"test_ingestor":{"code":200,"message":"OK"}}}""")

        wsClient.sendCompletion()
        wsClient.expectCompletion()
      }

    }

  }
}