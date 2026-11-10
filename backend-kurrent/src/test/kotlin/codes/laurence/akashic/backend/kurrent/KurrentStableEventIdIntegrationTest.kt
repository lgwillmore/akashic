package codes.laurence.akashic.backend.kurrent

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.kurrent.dbclient.AppendToStreamOptions
import io.kurrent.dbclient.EventData
import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.ReadStreamOptions
import io.kurrent.dbclient.StreamState
import io.kurrent.dbclient.WriteResult
import io.kurrent.dbclient.WrongExpectedVersionException
import java.util.UUID
import java.util.concurrent.ExecutionException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KurrentStableEventIdIntegrationTest {
  private lateinit var client: KurrentDBClient

  @BeforeEach
  fun setUp() {
    client = newClient()
  }

  @AfterEach
  fun tearDown() {
    client.shutdown()
  }

  @Test
  fun `exact retry with the original expected revision succeeds without duplicating events`() {
    val stream = streamId()
    val event = eventData()

    assertThat(append(stream, StreamState.noStream(), event).revision).isEqualTo(0)
    assertThat(append(stream, StreamState.noStream(), event).revision).isEqualTo(0)

    assertThat(read(stream).map { it.eventId }).containsExactly(event.eventId)
  }

  @Test
  fun `redelivery is idempotent only when it retains the original expected revision`() {
    val originalExpectationStream = streamId()
    val original = eventData("original")
    append(originalExpectationStream, StreamState.noStream(), original)
    val unrelated = eventData("unrelated")
    append(originalExpectationStream, StreamState.streamRevision(0), unrelated)

    assertThat(append(originalExpectationStream, StreamState.noStream(), original).revision)
        .isEqualTo(0)
    assertThat(read(originalExpectationStream).map { it.eventId })
        .containsExactly(original.eventId, unrelated.eventId)

    val currentExpectationStream = streamId()
    val redelivered = eventData("redelivered")
    append(currentExpectationStream, StreamState.noStream(), redelivered)

    assertThat(
            append(currentExpectationStream, StreamState.streamRevision(0), redelivered).revision)
        .isEqualTo(1)
    assertThat(read(currentExpectationStream).map { it.eventId })
        .containsExactly(redelivered.eventId, redelivered.eventId)

    val advancedStream = streamId()
    val redeliveredAfterAdvance = eventData("redelivered-after-advance")
    val unrelatedAfterAdvance = eventData("unrelated")
    append(advancedStream, StreamState.noStream(), redeliveredAfterAdvance)
    append(advancedStream, StreamState.streamRevision(0), unrelatedAfterAdvance)

    assertThat(
            append(advancedStream, StreamState.streamRevision(1), redeliveredAfterAdvance).revision)
        .isEqualTo(2)
    assertThat(read(advancedStream).map { it.eventId })
        .containsExactly(
            redeliveredAfterAdvance.eventId,
            unrelatedAfterAdvance.eventId,
            redeliveredAfterAdvance.eventId)
  }

  @Test
  fun `multi-event retry matches IDs at the original revision and ignores changed payloads`() {
    val stream = streamId()
    val first = eventData("first")
    val second = eventData("second")

    assertThat(append(stream, StreamState.noStream(), first, second).revision).isEqualTo(1)
    assertThat(append(stream, StreamState.noStream(), first, second).revision).isEqualTo(1)

    val changedFirst = eventData("changed-first", first.eventId)
    val changedSecond = eventData("changed-second", second.eventId)
    assertThat(append(stream, StreamState.noStream(), changedFirst, changedSecond).revision)
        .isEqualTo(1)
    assertThat(appendFailure(stream, StreamState.noStream(), second, first))
        .isInstanceOf(WrongExpectedVersionException::class)
    assertThat(appendFailure(stream, StreamState.noStream(), first, eventData("replacement")))
        .isInstanceOf(WrongExpectedVersionException::class)

    val unrelated = (1..128).map { eventData("unrelated-$it") }
    assertThat(append(stream, StreamState.streamRevision(1), *unrelated.toTypedArray()).revision)
        .isEqualTo(129)
    assertThat(append(stream, StreamState.noStream(), first, second).revision).isEqualTo(1)

    assertThat(read(stream)).hasSize(130)
    val expectedRange = read(stream, fromRevision = 0, maxCount = 2)
    assertThat(expectedRange.map { it.eventId }).containsExactly(first.eventId, second.eventId)
    assertThat(expectedRange.map { it.eventData.decodeToString() })
        .containsExactly("first", "second")
  }

  @Test
  fun `wrong expected revisions fail even when an event ID already exists elsewhere`() {
    val stream = streamId()
    val original = eventData("original")
    append(stream, StreamState.noStream(), original)

    val staleFailure = appendFailure(stream, StreamState.noStream(), eventData("different"))
    assertThat(staleFailure).isInstanceOf(WrongExpectedVersionException::class)
    assertThat((staleFailure as WrongExpectedVersionException).actualState)
        .isEqualTo(StreamState.streamRevision(0))

    val aheadFailure = appendFailure(stream, StreamState.streamRevision(10), original)
    assertThat(aheadFailure).isInstanceOf(WrongExpectedVersionException::class)
    assertThat((aheadFailure as WrongExpectedVersionException).actualState)
        .isEqualTo(StreamState.streamRevision(0))

    assertThat(read(stream).map { it.eventId }).containsExactly(original.eventId)
  }

  @Test
  fun `retry after a discarded append response succeeds from a fresh client`() {
    val stream = streamId()
    val event = eventData("response-discarded")
    append(stream, StreamState.noStream(), event)
    client.shutdown()
    client = newClient()

    assertThat(append(stream, StreamState.noStream(), event).revision).isEqualTo(0)
    assertThat(read(stream).map { it.eventId }).containsExactly(event.eventId)
  }

  private fun append(
      stream: String,
      expectedState: StreamState,
      vararg events: EventData,
  ): WriteResult =
      client
          .appendToStream(stream, AppendToStreamOptions.get().streamState(expectedState), *events)
          .get()

  private fun appendFailure(
      stream: String,
      expectedState: StreamState,
      vararg events: EventData,
  ): Throwable =
      try {
        append(stream, expectedState, *events)
        error("Expected append to fail")
      } catch (error: ExecutionException) {
        error.cause ?: error
      }

  private fun read(stream: String, fromRevision: Long? = null, maxCount: Long? = null) =
      client
          .readStream(
              stream,
              ReadStreamOptions.get().apply {
                if (fromRevision == null) fromStart() else fromRevision(fromRevision)
                maxCount?.let(::maxCount)
              })
          .get()
          .events
          .map { it.event }

  private fun eventData(payload: String = "event", eventId: UUID = UUID.randomUUID()): EventData =
      EventData.builderAsBinary(eventId, "characterization", payload.encodeToByteArray()).build()

  private fun streamId(): String = "stable-id-characterization-${UUID.randomUUID()}"

  private fun newClient(): KurrentDBClient = testKurrentClient()
}

private val WriteResult.revision: Long
  get() = (nextExpectedRevision as StreamState.StreamRevisionStreamState).toRawLong()
