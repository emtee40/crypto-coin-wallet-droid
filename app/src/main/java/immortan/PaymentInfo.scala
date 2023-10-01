package immortan

import java.util.Date

import fr.acinq.bitcoin.{ByteVector32, Satoshi, Transaction}
import fr.acinq.eclair._
import immortan.crypto.Tools.{Any2Some, ExtPubKeys, Fiat2Btc, SEPARATOR, StringList}
import immortan.utils.ImplicitJsonFormats._


case class SemanticOrder(id: String, order: Long)
case class RBFParams(ofTxid: ByteVector32, mode: Long)

object SemanticOrder {
  type SemanticGroup = Seq[TransactionDetails]
  private def orderIdOrBaseId(details: TransactionDetails) = details.description.semanticOrder.map(_.id).getOrElse(details.identity)
  private def orderOrMaxValue(details: TransactionDetails) = details.description.semanticOrder.map(_.order).getOrElse(Long.MaxValue)

  private def collapseChildren(items: SemanticGroup) = {
    items.tail.foreach(_.isExpandedItem = false)
    items.head.isExpandedItem = true
    items
  }

  def makeSemanticOrder(items: SemanticGroup): SemanticGroup =
    items.distinct.groupBy(orderIdOrBaseId).mapValues(_ sortBy orderOrMaxValue)
      .mapValues(collapseChildren).values.toList.sortBy(_.head.seenAt)(Ordering[Long].reverse)
      .flatten
}

sealed trait TransactionDescription {
  val semanticOrder: Option[SemanticOrder]
  val label: Option[String]
}

sealed trait TransactionDetails {
  var isExpandedItem: Boolean = true
  // We order items on UI by when they were first seen
  // We hide items depending on when they were updated
  def updatedAt: Long
  def seenAt: Long

  val date: Date = new Date(updatedAt)
  val description: TransactionDescription
  val identity: String
}

// Tx descriptions

case class TxInfo(txString: String, txidString: String, extPubsString: String, depth: Long, receivedSat: Satoshi, sentSat: Satoshi,
                  feeSat: Satoshi, seenAt: Long, updatedAt: Long, description: TxDescription, balanceSnapshot: MilliSatoshi,
                  fiatRatesString: String, incoming: Long, doubleSpent: Long) extends TransactionDetails {

  override val identity: String = txidString

  lazy val isIncoming: Boolean = 1L == incoming
  
  lazy val isDoubleSpent: Boolean = 1L == doubleSpent

  lazy val isConfirmed: Boolean = depth > 0

  lazy val fiatRateSnapshot: Fiat2Btc = to[Fiat2Btc](fiatRatesString)

  lazy val extPubs: ExtPubKeys = tryTo[ExtPubKeys](extPubsString).getOrElse(Nil)

  lazy val txid: ByteVector32 = ByteVector32.fromValidHex(txidString)

  lazy val tx: Transaction = Transaction.read(txString)
}

sealed trait TxDescription extends TransactionDescription {
  def canBeCPFPd: Boolean = cpfpBy.isEmpty && cpfpOf.isEmpty
  def withNewOrderCond(order: Option[SemanticOrder] = None): TxDescription
  def withNewLabel(label1: Option[String] = None): TxDescription
  def withNewCPFPBy(txid: ByteVector32): TxDescription

  def addresses: StringList
  def queryText(txid: ByteVector32): String
  val cpfpBy: Option[ByteVector32]
  val cpfpOf: Option[ByteVector32]
  val rbf: Option[RBFParams]
}

object TxDescription {
  final val RBF_CANCEL = 1
  final val RBF_BOOST = 2
}

case class PlainTxDescription(addresses: StringList,
                              label: Option[String] = None, semanticOrder: Option[SemanticOrder] = None,
                              cpfpBy: Option[ByteVector32] = None, cpfpOf: Option[ByteVector32] = None,
                              rbf: Option[RBFParams] = None) extends TxDescription { me =>

  override def queryText(txid: ByteVector32): String = txid.toHex + SEPARATOR + addresses.mkString(SEPARATOR) + SEPARATOR + label.getOrElse(new String)
  override def withNewOrderCond(order: Option[SemanticOrder] = None): TxDescription = if (semanticOrder.isDefined) me else copy(semanticOrder = order)
  override def withNewLabel(label1: Option[String] = None): TxDescription = copy(label = label1)
  override def withNewCPFPBy(txid: ByteVector32): TxDescription = copy(cpfpBy = txid.asSome)
}

case class FallbackTxDescription(label: Option[String] = None, semanticOrder: Option[SemanticOrder] = None,
                                 cpfpBy: Option[ByteVector32] = None, cpfpOf: Option[ByteVector32] = None,
                                 rbf: Option[RBFParams] = None) extends TxDescription { me =>

  override def queryText(txid: ByteVector32): String = txid.toHex + SEPARATOR + label.getOrElse(new String)
  override def withNewOrderCond(order: Option[SemanticOrder] = None): TxDescription = copy(semanticOrder = order)
  override def withNewLabel(label1: Option[String] = None): TxDescription = copy(label = label1)
  override def withNewCPFPBy(txid: ByteVector32): TxDescription = copy(cpfpBy = txid.asSome)
  def addresses: StringList = Nil
}