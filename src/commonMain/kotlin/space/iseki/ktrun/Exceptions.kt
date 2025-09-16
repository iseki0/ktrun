package space.iseki.ktrun

/**
 * Represents an I/O exception.
 *
 * @param message the detail message, or null if no detail message is provided.
 * @param cause the cause of this exception, or null if no cause is specified.
 */
expect open class IOException : Exception {
    constructor()
    constructor(message: String?)
    constructor(cause: Throwable?)
    constructor(message: String?, cause: Throwable?)
}



expect class AccessDeniedException: IOException {
   internal constructor(file: String, other: String?, reason: String)
}

expect class NoSuchFileException: IOException {
   internal constructor(file: String, other: String?, reason: String)
}

expect class NotDirectoryException: IOException {
   internal constructor(file: String)
}

