package ylabs.messaging

import Client.Messages._
import akka.actor.{ ActorSystem, Props }
import akka.pattern.ask
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import java.util.UUID
import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.util.Success

// this test depends on a running xmpp server (e.g. ejabberd) configured so that admin users can create unlimited users in your environment!
// see http://docs.ejabberd.im/admin/guide/configuration/#modregister for more details
@tags.RequiresEjabberd
class ClientTest extends WordSpec with Matchers with BeforeAndAfterEach {
  implicit var system: ActorSystem = _
  implicit val timeout = Timeout(5 seconds)

  val adminUsername = "admin"
  val adminPassword = "admin"
  def randomUsername = s"testuser-${UUID.randomUUID.toString.substring(9)}"

  "connects to the xmpp server" in new Fixture {
    val connected = adminUser ? Connect(adminUsername, adminPassword)
    connected.value.get shouldBe Success(Connected)
    adminUser ! Disconnect
  }

  "allows user registration and deletion" in new Fixture {
    val username = randomUsername

    adminUser ! Connect(adminUsername, adminPassword)
    adminUser ! RegisterUser(username, password = username)

    val connected1 = user1 ? Connect(username, password = username)
    connected1.value.get shouldBe Success(Connected)

    user1 ! DeleteUser
    val connected2 = user1 ? Connect(username, password = username)
    connected2.value.get.get match {
      case ConnectError(t) ⇒ //that's all we want to check
    }
  }

  "enables users to chat to each other" in new Fixture {
    val username1 = randomUsername
    val username2 = randomUsername

    adminUser ! Connect(adminUsername, adminPassword)
    adminUser ! RegisterUser(username1, password = username1)
    adminUser ! RegisterUser(username2, password = username2)

    user1 ! Connect(username1, password = username1)
    user2 ! Connect(username2, password = username2)

    val messageListener = TestProbe()
    user2 ! RegisterMessageListener(messageListener.ref)

    user1 ! ChatTo(username2)
    val testMessage = "unique test message" + UUID.randomUUID
    user1 ! SendMessage(username2, testMessage)

    messageListener.fishForMessage(3 seconds, "expected message to be delivered") {
      case MessageReceived(chat, message) if !message.getBody.contains("Welcome") ⇒
        chat.getParticipant should startWith(username1)
        message.getTo should startWith(username2)
        message.getBody shouldBe testMessage
        true
      case _ ⇒ false
    }

    user1 ! DeleteUser
    user2 ! DeleteUser
  }

  // "allows async chats" in new Fixture {
  //   val messageListener = TestProbe()
  //   client2 ! RegisterMessageListener(messageListener.ref)

  //   client1 ! Connect(user1, pass1)
  //   // client2 is not connected
  //   client1 ! ChatTo(user2)
  //   val testMessage = "async test message"
  //   client1 ! SendMessage(user2, testMessage)

  //   // sleep is bad, but I dunno how else to make this guaranteed async. 
  //   Thread.sleep(1000)
  //   client2 ! Connect(user2, pass2)

  //   messageListener.expectMsgPF(3 seconds, "expected message to be delivered") {
  //     case MessageReceived(chat, message) ⇒
  //       chat.getParticipant shouldBe s"$user1@$domain/Smack"
  //       message.getTo shouldBe s"$user2@$domain"
  //       message.getBody shouldBe testMessage
  //       true
  //   }

  //   client1 ! Disconnect
  //   client2 ! Disconnect
  // }

  trait Fixture {
    val adminUser = TestActorRef(Props[Client])
    val user1 = TestActorRef(Props[Client])
    val user2 = TestActorRef(Props[Client])

    // def withOneUser(block: () => Unit): Unit {

    // }
  }

  override def beforeEach() {
    system = ActorSystem()
  }

  override def afterEach() {
    system.shutdown()
  }
}
