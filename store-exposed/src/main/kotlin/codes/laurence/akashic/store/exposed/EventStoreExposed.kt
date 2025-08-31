package codes.laurence.akashic.store.exposed

import codes.laurence.akashic.core.event.DomainEvent
import codes.laurence.akashic.core.serialization.EventSerialization
import codes.laurence.akashic.core.store.CheckPoint
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction

data class CheckPointExposed(val id: Long) : CheckPoint

object Events : LongIdTable(name = "event_sourcing.events", columnName = "id") {
  val aggregateId = uuid("aggregate_id")
  val aggregateType = varchar("aggregate_type", 100)
  val eventId = uuid("event_id").uniqueIndex()
  val eventType = varchar("event_type", 200)
  val eventData = binary("event_data")
  val version = long("version")
  val createdAt = datetime("created_at")

  init {
    uniqueIndex(aggregateId, version)
    index(false, aggregateId)
    index(false, aggregateType)
  }
}

abstract class EventStorePostgres<EVENT : DomainEvent>(
    private val aggregateType: String,
    private val database: Database,
    private val serialization: EventSerialization<EVENT>
) : EventStore<EVENT, CheckPointExposed>, HasLogger {

  private val logger = aLogger("EventStorePostgres.$aggregateType")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override suspend fun appendAggregateEvents(
      expectedCurrentVersion: Long?,
      aggregateId: UUID,
      events: List<EVENT>
  ): Res<EventStorePersistResult> {
    if (events.isEmpty()) {
      return if (expectedCurrentVersion == null) {
        Res.Failure(IllegalStateException("Cannot store an aggregate without a first event"))
      } else {
        Res.Success(EventStorePersistResult(expectedCurrentVersion))
      }
    }

    return try {
          withContext(Dispatchers.IO) {
            transaction(database) {
              // Check current version
              val currentVersion =
                  Events.selectAll()
                      .where { Events.aggregateId eq aggregateId }
                      .orderBy(Events.version, SortOrder.DESC)
                      .limit(1)
                      .firstOrNull()
                      ?.get(Events.version)

              // Validate expected version
              when {
                expectedCurrentVersion == null && currentVersion != null -> {
                  throw ConflictException("Aggregate $aggregateId already exists")
                }

                expectedCurrentVersion != null && currentVersion == null -> {
                  throw NotFoundException(aggregateId.toString())
                }

                expectedCurrentVersion != null && currentVersion != expectedCurrentVersion -> {
                  throw ConflictException(
                      "Expected version $expectedCurrentVersion, actual $currentVersion")
                }
              }

              // Insert events
              val startVersion = (currentVersion ?: -1) + 1
              events.forEachIndexed { index, event ->
                Events.insert {
                  it[Events.aggregateId] = aggregateId
                  it[Events.aggregateType] = this@EventStorePostgres.aggregateType
                  it[eventId] = event.meta.eventId
                  it[eventType] = eventType(event)
                  it[eventData] = serialization.serialise(event)
                  it[version] = startVersion + index
                  it[createdAt] = DateTime.now()
                }
              }

              EventStorePersistResult(startVersion + events.size - 1)
            }
          }
        } catch (e: ConflictException) {
          Res.Failure(e)
        } catch (e: NotFoundException) {
          Res.Failure(e)
        } catch (e: Exception) {
          Res.Failure(e)
        }
        .let { result ->
          when (result) {
            is EventStorePersistResult -> Res.Success(result)
            is Exception -> Res.Failure(result)
            else -> Res.Failure(IllegalStateException("Unexpected result type"))
          }
        }
  }

  override suspend fun readEvents(
      aggregateId: UUID,
      afterVersion: Long?,
      maxCount: Long
  ): Res<List<CommitedEvent<EVENT>>> {
    if (!aggregateExists(aggregateId)) {
      return Res.Failure(NotFoundException(aggregateId.toString()))
    }
    return try {
      val events =
          withContext(Dispatchers.IO) {
            transaction(database) {
              val response =
                  Events.selectAll()
                      .where {
                        (Events.aggregateId eq aggregateId) and
                            (Events.version greater (afterVersion ?: -1))
                      }
                      .orderBy(Events.version, SortOrder.ASC)
                      .limit(maxCount.toInt())
              val events =
                  response.mapNotNull { row ->
                    val eventData = row[Events.eventData]
                    serialization.deserialise(eventData)?.let { event ->
                      CommitedEvent(version = row[Events.version], event = event)
                    }
                  }
              events
            }
          }
      Res.Success(events)
    } catch (e: NotFoundException) {
      Res.Failure(e)
    } catch (e: Exception) {
      Res.Failure(e)
    }
  }

  private fun aggregateExists(uUID: UUID): Boolean {
    return transaction(database) {
      Events.selectAll().where { Events.aggregateId eq uUID }.count() > 0
    }
  }

  override suspend fun subscribeToEvents(
      afterCheckpoint: CheckPointExposed?
  ): ReceiveChannel<List<SubEvent<EVENT, CheckPointExposed>>> {
    return scope.produce<List<SubEvent<EVENT, CheckPointExposed>>>(capacity = Channel.UNLIMITED) {
      var lastPosition = afterCheckpoint?.id ?: 0L

      while (!isClosedForSend) {
        try {
          val events =
              withContext(Dispatchers.IO) {
                transaction(database) {
                  Events.selectAll()
                      .where {
                        (Events.aggregateType eq aggregateType) and (Events.id greater lastPosition)
                      }
                      .orderBy(Events.id, SortOrder.ASC)
                      .limit(100)
                      .map { row ->
                        val eventData = row[Events.eventData]
                        val deserializedEvent = serialization.deserialise(eventData)
                        if (deserializedEvent != null) {
                          SubEvent(
                              checkPoint = CheckPointExposed(row[Events.id].value),
                              commitedEvent =
                                  CommitedEvent(
                                      version = row[Events.version], event = deserializedEvent))
                        } else null
                      }
                      .filterNotNull()
                }
              }
          send(events)
          events.lastOrNull()?.let { lastEvent -> lastPosition = lastEvent.checkPoint.id }

          // Poll every 1 second if no events found
          if (events.isEmpty()) {
            delay(1.seconds)
          }
        } catch (e: Exception) {
          // Log error and continue polling
          logger.error("Error in subscription polling", e)
          delay(5.seconds)
        }
      }
    }
  }

  abstract fun eventType(event: EVENT): String
}
