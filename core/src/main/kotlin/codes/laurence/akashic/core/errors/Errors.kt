package codes.laurence.akashic.core.errors

class ConflictException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class NotFoundException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
