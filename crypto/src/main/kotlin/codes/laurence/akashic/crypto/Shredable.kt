package codes.laurence.akashic.crypto

/**
 * A piece of personal data that may have been erased.
 *
 * Domain events and state hold personal data as `Shredable<T>`. While the data subject's key exists
 * the value is [Present]; once the key has been shredded every stored copy opens as [Shredded], and
 * the domain decides what that means. The framework never substitutes a fake value, so code that
 * needs one chooses it explicitly with [valueOr].
 */
sealed interface Shredable<out T : Any> {
  data class Present<T : Any>(val value: T) : Shredable<T>

  data object Shredded : Shredable<Nothing>
}

/** The value, or `null` once it has been shredded. */
fun <T : Any> Shredable<T>.valueOrNull(): T? = (this as? Shredable.Present<T>)?.value

/** The value, or [whenShredded] once it has been shredded. */
inline fun <T : Any> Shredable<T>.valueOr(whenShredded: () -> T): T =
    when (this) {
      is Shredable.Present -> value
      Shredable.Shredded -> whenShredded()
    }

/** Transforms a present value and leaves a shredded one shredded. */
inline fun <T : Any, R : Any> Shredable<T>.map(transform: (T) -> R): Shredable<R> =
    when (this) {
      is Shredable.Present -> Shredable.Present(transform(value))
      Shredable.Shredded -> Shredable.Shredded
    }
