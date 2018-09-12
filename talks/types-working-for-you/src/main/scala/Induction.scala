package klarna

import scala.annotation.implicitNotFound
import shapeless._, labelled._

object Induction extends App {

  // Let's create an static serializer for JSON!

  case class Persona(id: Long, name: String)

  val persona = Persona(1337, "Persona P")

}
