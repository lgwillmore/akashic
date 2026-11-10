package codes.laurence.akashic.backend.exposed

import assertk.assertThat
import assertk.assertions.isEqualTo
import codes.laurence.akashic.core.aggregate.CommandId
import codes.laurence.akashic.core.event.ProposedEvent
import codes.laurence.akashic.core.event.store.AppendResult
import codes.laurence.akashic.core.event.store.EventAppendPreparer
import codes.laurence.akashic.core.stubs.CounterEvent
import codes.laurence.akashic.core.stubs.CounterEventSerializer
import codes.laurence.akashic.core.stubs.CounterTypeNames
import java.sql.SQLException
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test

class AppendFailureClassificationTest {
  private val prepared =
      EventAppendPreparer(CounterTypeNames, CounterEventSerializer)
          .prepare(
              "counter",
              null,
              commandId = CommandId(Uuid.random().toString()),
              occurredAt = Clock.System.now(),
              events = listOf(ProposedEvent(CounterEvent.Started("counter", 0))))

  @Test
  fun `connection failure before transaction is definitely not committed`() {
    val cause = SQLException("connection unavailable", "08001")

    assertThat(cause.classifyAppendFailure(transactionBodyCompleted = false, prepared))
        .isEqualTo(AppendResult.Failure.NotCommitted(cause))
  }

  @Test
  fun `connection failure while committing has unknown outcome`() {
    val cause = SQLException("connection lost", "08006")

    assertThat(cause.classifyAppendFailure(transactionBodyCompleted = true, prepared))
        .isEqualTo(AppendResult.Failure.OutcomeUnknown(prepared, cause))
  }

  @Test
  fun `transaction rollback is definitely not committed`() {
    val cause = SQLException("serialization failure", "40001")

    assertThat(cause.classifyAppendFailure(transactionBodyCompleted = true, prepared))
        .isEqualTo(AppendResult.Failure.NotCommitted(cause))
  }

  @Test
  fun `statement completion unknown remains indeterminate`() {
    val cause = SQLException("completion unknown", "40003")

    assertThat(cause.classifyAppendFailure(transactionBodyCompleted = true, prepared))
        .isEqualTo(AppendResult.Failure.OutcomeUnknown(prepared, cause))
  }

  @Test
  fun `statement completion unknown before commit is definitely not committed`() {
    val cause = SQLException("statement completion unknown", "40003")

    assertThat(cause.classifyAppendFailure(transactionBodyCompleted = false, prepared))
        .isEqualTo(AppendResult.Failure.NotCommitted(cause))
  }

  @Test
  fun `non operational SQL error remains unexpected`() {
    val cause = SQLException("invalid data", "22000")

    assertThat(cause.classifyAppendFailure(transactionBodyCompleted = true, prepared))
        .isEqualTo(null)
  }
}
