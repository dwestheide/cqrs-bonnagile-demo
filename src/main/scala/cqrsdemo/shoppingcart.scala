package cqrsdemo

import java.util.UUID

trait Command[AggregateType] {
  def aggregateId: UUID
}
trait Event[AggregateType] {
  def aggregateId: UUID
  def pos: Int
}
trait AggregateRoot {
  def aggregateId: UUID
  def pos: Int
}

trait CommandHandler[AggregateType, CommandType, EventType] {
  def handle(command: CommandType, aggregate: AggregateType): EventType
}

trait WriteModelHandler[AggregateType, EventType] extends ((AggregateType, EventType) => AggregateType)

sealed trait ShoppingCartCommand extends Command[ShoppingCart]

case class AddItemCommand(
  productId: Int,
  quantity: Int,
  price: Int,
  aggregateId: UUID)
    extends ShoppingCartCommand

case class RemoveItemCommand(
  productId: Int,
  quantity: Int,
  aggregateId: UUID)
    extends ShoppingCartCommand

sealed trait ShoppingCartEvent extends Event[ShoppingCart]

case class ItemAddedEvent(
  productId: Int,
  quantity: Int,
  price: Int,
  aggregateId: UUID,
  pos: Int)
    extends ShoppingCartEvent

case class ItemRemovedEvent(
  productId: Int,
  quantity: Int,
  aggregateId: UUID,
  pos: Int)
    extends ShoppingCartEvent

case class CartItem(productId: Int, price: Int)
case class ShoppingCart(
  userId: Int,
  aggregateId: UUID = UUID.randomUUID(),
  pos: Int = 0,
  items: Map[Int, (CartItem, Int)] = Map.empty)
    extends AggregateRoot {

  def quantity(productId: Int): Int = (for {
    (item, quantity) <- items.get(productId)
  } yield quantity).getOrElse(0)

  def satisfiesMinimumQuantity(productId: Int, minimumQuantity: Int) = quantity(productId) >= minimumQuantity

  def +(item: CartItem, quantity: Int) = {
    val (cartItem, q) = items.getOrElse(item.productId, (item, 0))
    copy(items = items + (item.productId -> (cartItem, q + quantity)))
  }

  def -(productId: Int, quantity: Int) = {
    val (cartItem, q) = items.get(productId).get
    val updatedQuantity = q - quantity
    val updatedItems = if (updatedQuantity == 0) items - productId else items + (productId -> (cartItem, updatedQuantity))
    copy(items = updatedItems)
  }
}

case class NotEnoughItemsInCartException(cartId: UUID, productId: Int, requiredQuantity: Int, actualQuantity: Int)
 extends Exception

class ShoppingCartHandler
  extends CommandHandler[ShoppingCart, ShoppingCartCommand, ShoppingCartEvent]
  with WriteModelHandler[ShoppingCart, ShoppingCartEvent] {

  def handle(command: ShoppingCartCommand, cart: ShoppingCart) = command match {
    case AddItemCommand(productId, quantity, price, shoppingCartId) =>
      ItemAddedEvent(productId, quantity, price, shoppingCartId, cart.pos + 1)
    case RemoveItemCommand(productId, quantity, shoppingCartId) =>
      if (cart.satisfiesMinimumQuantity(productId, quantity))
        ItemRemovedEvent(productId, quantity, shoppingCartId, cart.pos + 1)
      else throw NotEnoughItemsInCartException(shoppingCartId, productId, quantity, cart.quantity(productId))
  }

  def apply(cart: ShoppingCart, event: ShoppingCartEvent) = event match {
    case ItemAddedEvent(productId, quantity, price, _, eventPos) =>
      cart.copy(pos = eventPos) + (CartItem(productId, price), quantity)
    case ItemRemovedEvent(productId, quantity, _, eventPos) =>
      cart.copy(pos = eventPos) - (productId, quantity)
  }
}

object Repository {
  val handler = new ShoppingCartHandler
  def loadCurrentState(userId: Int, cartId: UUID, eventStream: Seq[ShoppingCartEvent]): ShoppingCart = {
    val cart = ShoppingCart(userId, cartId)
    eventStream.foldLeft(cart)(handler)
  }
}
