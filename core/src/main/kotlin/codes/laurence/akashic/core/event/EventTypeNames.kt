package codes.laurence.akashic.core.event

import kotlin.reflect.KClass

/**
 * Registry of stable persisted names for domain event classes. Naming is kept separate from payload
 * encoding because stores also use these names for routing and filtering.
 */
sealed interface EventTypeNames<EVENT : Any> {
  val events: Map<KClass<out EVENT>, String>

  fun eventType(event: EVENT): String

  /** Event names scoped to an [aggregate] type. */
  data class ForAggregate<EVENT : Any>(
      val aggregate: String,
      override val events: Map<KClass<out EVENT>, String>
  ) : EventTypeNames<EVENT> {
    override fun eventType(event: EVENT): String =
        events[event::class]
            ?: throw IllegalArgumentException(
                "Unknown event type name for $aggregate: ${event::class.simpleName}")
  }

  /** Event names for a global read or subscription scope. */
  data class Global<EVENT : Any>(override val events: Map<KClass<out EVENT>, String>) :
      EventTypeNames<EVENT> {
    override fun eventType(event: EVENT): String =
        events[event::class]
            ?: throw IllegalArgumentException("Unknown event type name: ${event::class.simpleName}")
  }
}
