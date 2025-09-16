package space.iseki.ktrun

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(cause: Throwable?) : super(cause)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

private fun buildMessageForFSException(file: String, other: String?, reason: String): String {
    return buildString {
        append(file)
        if (other != null) {
            append(" -> ")
            append(other)
        }
        if (reason.isNotEmpty()) {
            append(": ")
            append(reason)
        }
    }
}

actual open class AccessDeniedException internal actual constructor(val file: String, val other: String?, val reason: String) :
    IOException() {
    override val message: String? get() = buildMessageForFSException(file, other, reason)
}

actual open class NoSuchFileException internal actual constructor(val file: String, val other: String?, val reason: String) :
    IOException() {
    override val message: String? get() = buildMessageForFSException(file, other, reason)
}

actual open class NotDirectoryException internal actual constructor(val file: String) :
    IOException(file) {
}


