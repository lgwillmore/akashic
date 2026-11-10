package codes.laurence.akashic.core

/**
 * Marks declarations that Akashic's own backend modules share with the core but that are not part
 * of the public API. They may change or disappear in any release without notice.
 */
@RequiresOptIn(
    message =
        "This is an internal Akashic API for its backend modules and may change without notice",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class AkashicInternalApi
