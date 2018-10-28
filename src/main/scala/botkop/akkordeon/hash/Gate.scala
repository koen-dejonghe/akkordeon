package botkop.akkordeon.hash

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import scorch.autograd.Variable
import scorch.nn.Module
import scorch.optim.Optimizer

case class Gate(module: Module, optimizer: Optimizer, name: String)
    extends Stageable {
  def stage(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new GateActor(this)), name)
}

class GateActor(gate: Gate) extends Actor with ActorLogging {

  import gate._

  var wire: Wire = _

  override def receive: Receive = {
    case w: Wire =>
      log.debug(s"received wire $w")
      this.wire = w
      context become messageHandler(Map.empty)
    case u =>
      log.error(s"$name: receive: unknown message ${u.getClass.getName}")
  }

  def messageHandler(activations: Map[String, (Variable, Variable)]): Receive = {

    case Validate(sentinel, x, y) =>
      wire.next.getOrElse(sentinel) ! Validate(sentinel, module(x), y)

    case Forward(id, sentinel, x, y) =>
      val result = module(x)
      wire.next.getOrElse(sentinel) ! Forward(id, sentinel, result, y)
      context become messageHandler(activations + (id -> (x, result)))

    case Backward(id, sentinel, g) =>
      val (input, output) = activations(id)
      optimizer.zeroGrad()
      output.backward(g)
      wire.prev.getOrElse(sentinel) ! Backward(id, sentinel, input.grad)
      optimizer.step()
      context become messageHandler(activations - id)

    case u =>
      log.error(s"$name: messageHandler: unknown message ${u.getClass.getName}")
  }

}
