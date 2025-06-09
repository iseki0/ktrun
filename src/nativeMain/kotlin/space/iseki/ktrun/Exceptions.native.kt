package space.iseki.ktrun

/**
 * Represents an I/O exception.
 *
 * @param message the detail message, or null if no detail message is provided.
 * @param cause the cause of this exception, or null if no cause is specified.
 */
actual open class IOException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause) {
    actual constructor(message: String?) : this(message, null)

    actual constructor(cause: Throwable?) : this(null, cause)
}
