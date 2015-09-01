package ylabs.messaging

import akka.actor.{ ActorSystem, Props }
import scala.collection.JavaConversions._
import Client.{ User, Password }

object ChatApp extends App {
  import Client.Messages._

  val system = ActorSystem()
  val chattie = system.actorOf(Props[Client], "chatClient")

  var on = true
  computerSays("What now?")
  while (on) {
    io.StdIn.readLine match {
      case "connect" ⇒
        println("username: "); val username = io.StdIn.readLine
        val password = username
        // println("password: "); val password = io.StdIn.readLine
        // val username = "admin5"
        // val password = "admin5"
        chattie ! Connect(User(username), Password(password))

      case "openchat" ⇒
        computerSays("who may i connect you with, sir?")
        val user = User(io.StdIn.readLine)
        chattie ! ChatTo(user)

      case "message" ⇒
        computerSays("who do you want to send a message to, sir?")
        val user = User(io.StdIn.readLine)
        computerSays("what's your message, sir?")
        val message = io.StdIn.readLine
        chattie ! SendMessage(user, message)

      case "leavechat" ⇒
        computerSays("who may i disconnect you from, sir?")
        val user = User(io.StdIn.readLine)
        chattie ! LeaveChat(user)

      case "disconnect" ⇒
        chattie ! Disconnect

      case "exit" ⇒
        computerSays("shutting down")
        system.shutdown()
        on = false

      case _ ⇒ computerSays("Que? No comprendo. Try again, sir!")
    }
  }

  def computerSays(s: String) = println(s">> $s")
}