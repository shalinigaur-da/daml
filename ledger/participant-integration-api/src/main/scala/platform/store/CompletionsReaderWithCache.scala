package com.daml.platform.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.ApplicationId
import com.daml.ledger.api.v1.command_completion_service.CompletionStreamResponse
import com.daml.ledger.participant.state.v1.Offset
import com.daml.lf.data.Ref
import com.daml.logging.LoggingContext
import com.daml.platform.store.dao.{CommandCompletionsReader, LedgerReadDao}

import scala.concurrent.ExecutionContext

class CompletionsReaderWithCache(ledgerDao: LedgerReadDao) { // TODO missing size limit params

  private var cacheStartExclusive: Offset = _
  private var cacheEndInclusive: Offset = _

  private val cache = new CompletionsCache()

  def getCommandCompletions(
      startExclusive: Offset,
      endInclusive: Offset,
      applicationId: ApplicationId,
      parties: Set[Ref.Party],
  )(implicit
      loggingContext: LoggingContext
  ): Source[(Offset, CompletionStreamResponse), NotUsed] = { // if is historic not synced, else synced
    if (isHistoric(startExclusive)) {
      fetchHistoric(startExclusive, endInclusive, applicationId, parties)
    } else {
      readFromCache(startExclusive, endInclusive, applicationId, parties)
        .concat(fetchFuture(startExclusive, endInclusive, applicationId, parties))
    }
  }

  private def fetchHistoric(
      startExclusive: Offset,
      endInclusive: Offset,
      applicationId: ApplicationId,
      parties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContext): Source[(Offset, CompletionStreamResponse), NotUsed] =
    ledgerDao.completions
      .getCommandCompletions(startExclusive, endInclusive, applicationId, parties)

  private def fetchFuture(
      startExclusive: Offset,
      endInclusive: Offset,
      applicationId: ApplicationId,
      parties: Set[Ref.Party],
  )(implicit
      loggingContext: LoggingContext,
      executionContext: ExecutionContext,
  ): Source[(Offset, CompletionStreamResponse), NotUsed] = {
    val futureCompletions = ledgerDao.completions
      .getAllCommandCompletions(startExclusive, endInclusive)
    futureCompletions.foreach(cache.set)
    Source
      .future(futureCompletions)
      .mapConcat(_.map { case (offset, completionsWithParties) =>
        (offset, completionsWithParties.completion)
      }).filter {
      case (_, response) => // TODO filterBy applicationId and parties
    }
  }

  private def readFromCache(
      startExclusive: Offset,
      endInclusive: Offset,
      applicationId: ApplicationId,
      parties: Set[Ref.Party],
  ): Source[(Offset, CompletionStreamResponse), NotUsed] = ???

  private def isHistoric(startExclusive: Offset): Boolean = startExclusive < cacheStartExclusive
}
