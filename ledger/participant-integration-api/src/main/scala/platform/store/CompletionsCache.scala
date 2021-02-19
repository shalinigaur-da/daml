package com.daml.platform.store

import com.daml.ledger.ApplicationId
import com.daml.ledger.api.v1.command_completion_service.CompletionStreamResponse
import com.daml.ledger.participant.state.v1.Offset
import com.daml.lf.data.Ref
import com.daml.platform.store.dao.CommandCompletionsTable.CompletionStreamResponseWithParties

import scala.collection.mutable

class CompletionsCache {

  private val cache
      : mutable.Map[ApplicationId, mutable.SortedMap[Offset, CompletionStreamResponseWithParties]] =
    mutable.Map[ApplicationId, mutable.SortedMap[Offset, CompletionStreamResponseWithParties]]()

  def get(
      startExclusive: Offset,
      endInclusive: Offset,
      applicationId: ApplicationId,
      parties: Set[Ref.Party],
  ): Seq[(Offset, CompletionStreamResponse)] = {
    cache.get(applicationId).fold[Seq[(Offset, CompletionStreamResponse)]](Nil) { appCompletions =>
      val rangeCompletions = appCompletions.range(startExclusive, endInclusive)
      rangeCompletions.remove(startExclusive)
      appCompletions.get(endInclusive).foreach(rangeCompletions.put(endInclusive, _))
      rangeCompletions.filter { case (_, completions) =>
        completions.parties.exists(parties.contains)
      }.toList.map {
        case (offset, completionsWithParties) => (offset, completionsWithParties.completion)
      }
    }
  }

  def set(completions: Seq[(Offset, CompletionStreamResponseWithParties)]): Unit = ???
}
