package smack.scala

import _root_.smack.scala.Client.{ MessageId, Password, User }
import Client.Messages._
import akka.actor.{ ActorSystem, Props }
import akka.pattern.ask
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import java.util.UUID
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.roster.Roster
import org.scalatest
import org.scalatest.exceptions.TestFailedException
import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }
import scala.collection.JavaConversions._
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

// this test depends on a running xmpp server (e.g. ejabberd) configured so that admin users can create unlimited users in your environment!
// see http://docs.ejabberd.im/admin/guide/configuration/#modregister for more details
@tags.RequiresEjabberd
class ClientTest extends WordSpec with Matchers with BeforeAndAfterEach {
  implicit var system: ActorSystem = _
  implicit val timeout = Timeout(5 seconds)

  val config = ConfigFactory.load
  val adminUsername = config.getString("messaging.admin.username")
  val adminPassword = config.getString("messaging.admin.password")
  val domain = config.getString("messaging.domain")

  "A client" when {
    "usernames don't have domains " should {
      "connects to the xmpp server" in new TestFunctions {
        connected
      }

      "allows user registration and deletion" in new TestFunctions {
        registration
      }

      "should reject duplicate registration" in new TestFunctions {
        sameRegistration
      }

      "should reject registration with username same as domain" in new TestFunctions {
        invalidRegistration
      }

      "enables users to chat to each other" in new TestFunctions {
        chat
      }

      "enables async chats (message recipient offline)" in new TestFunctions {
        asyncChat
      }

      "enables XEP-0066 file transfers" in new TestFunctions {
        XEP_0066_FileTransfers
      }

      "informs event listeners about chat partners becoming available / unavailable" in new TestFunctions {
        availability
      }

      "provides information about who is online and offline (roster)" in new TestFunctions {
        roster
      }

      "message receiver subscribes to sender" in new TestFunctions {
        receiverConnects
      }

      "message delivered acknowledgement" in new TestFunctions {
        deliveryAcknowledged
      }

      "async message delivered acknowledgement" in new TestFunctions {
        asyncDeliveryAck
      }
    }

    "usernames have domains " should {
      "connects to the xmpp server" in new TestFunctionsWithDomain {
        connected
      }

      "allows user registration" in new TestFunctionsWithDomain {
        registration
      }

      "should reject duplicate registration" in new TestFunctionsWithDomain {
        sameRegistration
      }

      "should reject registration with username same as domain" in new TestFunctionsWithDomain {
        invalidRegistration
      }

      "enables users to chat to each other" in new TestFunctionsWithDomain {
        chat
      }

      "enables async chats (message recipient offline)" in new TestFunctionsWithDomain {
        asyncChat
      }

      "enables XEP-0066 file transfers" in new TestFunctionsWithDomain {
        XEP_0066_FileTransfers
      }

      "informs event listeners about chat partners becoming available / unavailable" in new TestFunctionsWithDomain {
        availability
      }

      "provides information about who is online and offline (roster)" in new TestFunctionsWithDomain {
        roster
      }

      "message receiver subscribes to sender" in new TestFunctionsWithDomain {
        receiverConnects
      }

      "message delivered acknowledgement" in new TestFunctionsWithDomain {
        deliveryAcknowledged
      }

      "async message delivered acknowledgement" in new TestFunctionsWithDomain {
        asyncDeliveryAck
      }
    }
  }

  class TestFunctions extends AnyRef with Fixture {
    def connected: Unit = {
      val connected = adminUser ? Connect(User(adminUsername), Password(adminPassword))
      connected.value.get shouldBe Success(Connected)
      adminUser ! Disconnect
    }

    def registration: Unit = {
      val username = randomUsername
      val userPass = Password(username.value)

      val connected = adminUser ? Connect(User(adminUsername), Password(adminPassword))
      adminUser ! RegisterUser(username, userPass)

      val connected1 = user1 ? Connect(username, userPass)
      connected1.value.get shouldBe Success(Connected)

      user1 ! DeleteUser
      val connected2 = user1 ? Connect(username, userPass)
      connected2.value.get.get match {
        case ConnectError(t) ⇒ //that's all we want to check
      }
    }

    def sameRegistration: Unit = {
      val username = randomUsername
      val userPass = Password(username.value)

      val connected = adminUser ? Connect(User(adminUsername), Password(adminPassword))
      adminUser ! RegisterUser(username, userPass)
      val registration = adminUser ? RegisterUser(username, userPass)
      registration.value.get shouldBe Failure(DuplicateUser(username))
    }

    def invalidRegistration: Unit = {
      val username = User(domain);
      val userPass = Password(username.value)

      val connected = adminUser ? Connect(User(adminUsername), Password(adminPassword))
      val registration = adminUser ? RegisterUser(username, userPass)
      registration.value.get shouldBe Failure(InvalidUserName(username))
    }

    def chat: Unit = {
      withTwoConnectedUsers {
        user1 ! SendMessage(username2, testMessage)
        verifyMessageArrived(user2Listener, username1, username2, testMessage)
      }
    }

    def asyncChat: Unit = {
      withTwoUsers {
        user1 ! Connect(username1, user1Pass)
        val user2Listener = newEventListener
        user2 ! RegisterEventListener(user2Listener.ref)

        user1 ! SendMessage(username2, testMessage)

        // yeah, sleeping is bad, but I dunno how else to make this guaranteed async.
        Thread.sleep(1000)
        user2 ! Connect(username2, user2Pass)

        verifyMessageArrived(user2Listener, username1, username2, testMessage)
      }
    }

    def XEP_0066_FileTransfers = {
      withTwoConnectedUsers {
        val fileUrl = "https://raw.githubusercontent.com/mpollmeier/gremlin-scala/master/README.md"
        val fileDescription = Some("file description")
        user1 ! SendFileMessage(username2, fileUrl, fileDescription)

        user2Listener.expectMsgPF(3 seconds, "xep-0066 file transfer") {
          case FileMessageReceived(chat, message, outOfBandData) ⇒
            chat.getParticipant should startWith(username1.value)
            message.getTo should startWith(username2.value)
            outOfBandData.url shouldBe fileUrl
            outOfBandData.desc shouldBe fileDescription
        }
      }
    }

    def availability = {
      withTwoConnectedUsers {
        user1 ! SendMessage(username2, testMessage)
        verifyMessageArrived(user2Listener, username1, username2, testMessage)
        user1Listener.fishForMessage(3 seconds, "notification that user2 came online") {
          case UserBecameAvailable(user) ⇒
            user.value should startWith(username2.value)
            true
          case _ ⇒ false
        }

        user2 ! Disconnect
        user1Listener.fishForMessage(3 seconds, "notification that user2 went offline") {
          case UserBecameUnavailable(user) ⇒
            user.value should startWith(username2.value)
            true
          case _ ⇒ false
        }

        user2 ! Connect(username2, user2Pass)
        user1Listener.fishForMessage(3 seconds, "notification that user2 came online") {
          case UserBecameAvailable(user) ⇒
            user.value should startWith(username2.value)
            true
          case _ ⇒ false
        }
      }
    }

    def deliveryAcknowledged = {
      withTwoConnectedUsers {
        val user1MessageId = user1 ? SendMessage(username2, testMessage)
        verifyMessageArrived(user2Listener, username1, username2, testMessage)
        verifyMessageDelivery(user1Listener, username1, username2, user1MessageId)
      }
    }

    def asyncDeliveryAck: Unit = {
      withTwoUsers {
        user1 ! Connect(username1, user1Pass)
        val user2Listener = newEventListener
        user2 ! RegisterEventListener(user2Listener.ref)

        val user1MessageId = user1 ? SendMessage(username2, testMessage)

        // yeah, sleeping is bad, but I dunno how else to make this guaranteed async.
        Thread.sleep(1000)
        user2 ! Connect(username2, user2Pass)

        verifyMessageArrived(user2Listener, username1, username2, testMessage)
        verifyMessageDelivery(user1Listener, username1, username2, user1MessageId)
      }
    }

    def roster = {
      withTwoConnectedUsers {
        user1 ! SendMessage(username2, testMessage)
        verifyMessageArrived(user2Listener, username1, username2, testMessage)

        user1Listener.fishForMessage(3 seconds, "notification that user2 is in roster") {
          case UserBecameAvailable(user) ⇒
            val roster = getRoster(user1)
            roster.getEntries should have size 1
            val entry = roster.getEntries.head
            entry.getUser should startWith(username2.value)
            roster.getPresence(entry.getUser).getType shouldBe Presence.Type.available
            true
          case _ ⇒ false
        }

        user2 ! Disconnect
        user1Listener.fishForMessage(3 seconds, "notification that user2 is not in roster") {
          case UserBecameUnavailable(user) ⇒
            val roster = getRoster(user1)
            roster.getEntries should have size 1
            val entry = roster.getEntries.head
            entry.getUser should startWith(username2.value)
            roster.getPresence(entry.getUser).getType shouldBe Presence.Type.unavailable
            true
          case _ ⇒ false
        }
      }
    }

    def receiverConnects = {
      withTwoConnectedUsers {
        user1 ! SendMessage(username2, testMessage)
        verifyMessageArrived(user2Listener, username1, username2, testMessage)

        user2Listener.fishForMessage(3 seconds, "notification that user1 is in roster") {
          case UserBecameAvailable(user) ⇒
            val roster = getRoster(user2)
            roster.getEntries should have size 1
            val entry = roster.getEntries.head
            entry.getUser should startWith(username1.value)
            roster.getPresence(entry.getUser).getType shouldBe Presence.Type.available
            true
        }
      }
    }

    private def getRoster(u: TestActorRef[Nothing]): Roster = {
      val rosterFuture = (u ? GetRoster).mapTo[GetRosterResponse]
      Await.result(rosterFuture, 3 seconds).roster
    }
  }

  class TestFunctionsWithDomain extends TestFunctions with FixtureWithDomain {
    assert(username1.value.contains("@"))
    assert(username2.value.contains("@"))
  }

  trait Fixture {
    val adminUser = TestActorRef(Props[Client])
    val user1 = TestActorRef(Props[Client])
    val user2 = TestActorRef(Props[Client])
    val username1 = randomUsername
    val username2 = randomUsername
    val user1Pass = Password(username1.value)
    val user2Pass = Password(username2.value)
    val user1Listener = newEventListener
    val user2Listener = newEventListener

    val testMessage = "unique test message" + UUID.randomUUID

    def newEventListener: TestProbe = {
      val eventListener = TestProbe()
      eventListener.ignoreMsg {
        case MessageReceived(_, message) ⇒ message.getSubject == "Welcome!"
      }
      eventListener
    }

    def withTwoUsers(block: ⇒ Unit): Unit = {
      adminUser ! Connect(User(adminUsername), Password(adminPassword))
      adminUser ! RegisterUser(username1, Password(username1.value))
      adminUser ! RegisterUser(username2, Password(username2.value))

      user1 ! RegisterEventListener(user1Listener.ref)
      user2 ! RegisterEventListener(user2Listener.ref)
      try {
        block
      } finally {
        user1 ! DeleteUser
        user2 ! DeleteUser
      }
    }

    def withTwoConnectedUsers(block: ⇒ Unit): Unit =
      withTwoUsers {
        user1 ! Connect(username1, user1Pass)
        user2 ! Connect(username2, user2Pass)
        block
      }

    def verifyMessageArrived(testProbe: TestProbe, sender: User, recipient: User, messageBody: String): Unit = {
      testProbe.fishForMessage(3 seconds, "expected message to be delivered") {
        case MessageReceived(chat, message) ⇒
          chat.getParticipant should startWith(sender.value)
          message.getTo should startWith(recipient.value)
          message.getBody shouldBe messageBody
          true
        case _ ⇒ false
      }
    }

    def verifyMessageDelivery(testProbe: TestProbe, sender: User, recipient: User, messageIdFuture: Future[Any]): Unit = {
      testProbe.fishForMessage(3 seconds, "notification that message has been delivered") {
        case MessageDelivered(to, msgId) ⇒
          messageIdFuture.value.get.get match {
            case MessageId(messageId) ⇒ {
              to.value should startWith(recipient.value)
              msgId.value should equal(messageId)
            }
          }
          true
        case _ ⇒ false
      }
    }
  }

  trait FixtureWithDomain extends Fixture {
    override val username1 = nameWithDomain(randomUsername)
    override val username2 = nameWithDomain(randomUsername)
    override val user1Pass = Password(username1.value)
    override val user2Pass = Password(username2.value)
  }

  def randomUsername = User(s"testuser-${UUID.randomUUID.toString.substring(9)}")
  def nameWithDomain(u: User) = u.copy(value = u.value + s"@$domain")

  override def beforeEach() {
    system = ActorSystem()
  }

  override def afterEach() {
    system.shutdown()
  }
}