// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao.events

import java.time.Instant

import com.daml.ledger.{TransactionId, WorkflowId}
import com.daml.ledger.participant.state.v1.{CommittedTransaction, ContractInst, DivulgedContract, Offset, SubmitterInfo}
import com.daml.lf.ledger.EventId
import com.daml.lf.transaction.BlindingInfo

final case class TransactionIndexing(
    transaction: TransactionIndexing.TransactionInfo,
    events: TransactionIndexing.EventsInfo,
    contracts: TransactionIndexing.ContractsInfo,
)

object TransactionIndexing {

  def serialize(
      translation: LfValueTranslation,
      transactionId: TransactionId,
      events: Vector[(NodeId, Node)],
      divulgedContracts: Iterable[DivulgedContract],
  ): Serialized = {

    val createArgumentsByContract = Map.newBuilder[ContractId, Array[Byte]]
    val createArguments = Map.newBuilder[NodeId, Array[Byte]]
    val createKeyValues = Map.newBuilder[NodeId, Array[Byte]]
    val createKeyHashes = Map.newBuilder[NodeId, Array[Byte]]
    val exerciseArguments = Map.newBuilder[NodeId, Array[Byte]]
    val exerciseResults = Map.newBuilder[NodeId, Array[Byte]]

    for ((nodeId, event) <- events) {
      val eventId = EventId(transactionId, nodeId)
      event match {
        case create: Create =>
          val (createArgument, createKeyValue) = translation.serialize(eventId, create)
          createArgumentsByContract += ((create.coid, createArgument))
          createArguments += ((nodeId, createArgument))
          createKeyValue.foreach(key => createKeyValues += ((nodeId, key)))
          createKeyValue.foreach(_ => createKeyHashes += ((nodeId, create.key
            .map(convertLfValueKey(create.templateId, _))
            .map(_.hash.bytes.toByteArray)
            .orNull)))
        case exercise: Exercise =>
          val (exerciseArgument, exerciseResult) = translation.serialize(eventId, exercise)
          exerciseArguments += ((nodeId, exerciseArgument))
          exerciseResult.foreach(result => exerciseResults += ((nodeId, result)))
        case _ => throw new UnexpectedNodeException(nodeId, transactionId)
      }
    }

    for (DivulgedContract(contractId, contractInst) <- divulgedContracts) {
      val serializedCreateArgument = translation.serialize(contractId, contractInst.arg)
      createArgumentsByContract += ((contractId, serializedCreateArgument))
    }

    Serialized(
      createArgumentsByContract = createArgumentsByContract.result(),
      createArguments = createArguments.result(),
      createKeyValues = createKeyValues.result(),
      createKeyHashes = createKeyHashes.result(),
      exerciseArguments = exerciseArguments.result(),
      exerciseResults = exerciseResults.result(),
    )

  }

  private class Builder(blinding: BlindingInfo) {

    private val events = Vector.newBuilder[(NodeId, Node)]
    private val stakeholders = Map.newBuilder[NodeId, Set[Party]]
    private val disclosure = Map.newBuilder[NodeId, Set[Party]]

    private def addEventAndDisclosure(event: (NodeId, Node)): Unit = {
      events += event
      disclosure += ((event._1, blinding.disclosure(event._1)))
    }

    private def addStakeholders(nodeId: NodeId, parties: Set[Party]): Unit =
      stakeholders += ((nodeId, parties))

    def add(event: (NodeId, Node)): Builder = {
      event match {
        case (nodeId, create: Create) =>
          addEventAndDisclosure(event)
          addStakeholders(nodeId, create.stakeholders)
        case (nodeId, exercise: Exercise) =>
          addEventAndDisclosure(event)
          if (exercise.consuming) {
            addStakeholders(nodeId, exercise.stakeholders)
          } else {
            addStakeholders(nodeId, Set.empty)
          }
        case _ =>
          () // ignore anything else
      }
      this
    }

    def build(
        submitterInfo: Option[SubmitterInfo],
        workflowId: Option[WorkflowId],
        transactionId: TransactionId,
        ledgerEffectiveTime: Instant,
        offset: Offset,
        divulgedContracts: Iterable[DivulgedContract],
    ): TransactionIndexing = {
      val divulgedContractIndex = divulgedContracts
        .map(divulgedContract => divulgedContract.contractId -> divulgedContract)
        .toMap
      val netDivulged = blinding.divulgence.map {
        case (contractId, visibleToParties) =>
          TransactionDivulgedContract(
            contractId = contractId,
            contractInst = divulgedContractIndex.get(contractId).map(_.contractInst),
            visibility = visibleToParties
          )
      }
      TransactionIndexing(
        transaction = TransactionInfo(
          submitterInfo = submitterInfo,
          workflowId = workflowId,
          transactionId = transactionId,
          ledgerEffectiveTime = ledgerEffectiveTime,
          offset = offset,
        ),
        events = EventsInfo(
          events = events.result(),
          stakeholders = stakeholders.result(),
          disclosure = disclosure.result(),
        ),
        contracts = ContractsInfo(
          divulgedContracts = netDivulged,
        ),
      )
    }
  }

  final case class TransactionInfo(
      submitterInfo: Option[SubmitterInfo],
      workflowId: Option[WorkflowId],
      transactionId: TransactionId,
      ledgerEffectiveTime: Instant,
      offset: Offset,
  )

  final case class EventsInfo(
      events: Vector[(NodeId, Node)],
      stakeholders: WitnessRelation[NodeId],
      disclosure: WitnessRelation[NodeId],
  )

  // most involved change: divulgence is now an event instead encoded in contract/contract_witness tables
  // also we generate these events directly as part of the other events, so we need to land the necessery
  // data in the EventsTablePostgresql
  case class TransactionDivulgedContract(contractId: ContractId,
                                         contractInst: Option[ContractInst], // this we do not necessarily have: for public KV ledgers the divulged contract list is empty, since all contracts must be at all ledger as create nodes already
                                         visibility: Set[Party])

  final case class ContractsInfo(
      divulgedContracts: Iterable[TransactionDivulgedContract],
  )

  final case class ContractWitnessesInfo(
      netArchives: Set[ContractId],
      netVisibility: WitnessRelation[ContractId],
  )

  final case class Serialized(
      createArgumentsByContract: Map[ContractId, Array[Byte]],
      createArguments: Map[NodeId, Array[Byte]],
      createKeyValues: Map[NodeId, Array[Byte]],
      createKeyHashes: Map[NodeId, Array[Byte]],
      exerciseArguments: Map[NodeId, Array[Byte]],
      exerciseResults: Map[NodeId, Array[Byte]],
  )

  def from(
      blindingInfo: BlindingInfo,
      submitterInfo: Option[SubmitterInfo],
      workflowId: Option[WorkflowId],
      transactionId: TransactionId,
      ledgerEffectiveTime: Instant,
      offset: Offset,
      transaction: CommittedTransaction,
      divulgedContracts: Iterable[DivulgedContract],
  ): TransactionIndexing =
    transaction
      .fold(new Builder(blindingInfo))(_ add _)
      .build(
        submitterInfo = submitterInfo,
        workflowId = workflowId,
        transactionId = transactionId,
        ledgerEffectiveTime = ledgerEffectiveTime,
        offset = offset,
        divulgedContracts = divulgedContracts,
      )

}
