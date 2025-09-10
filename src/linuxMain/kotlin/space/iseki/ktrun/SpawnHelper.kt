package space.iseki.ktrun

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

@OptIn(ObsoleteWorkersApi::class)
internal class SpawnHelper(private val fd: OsFd) {
    init {
        Worker.start().execute(TransferMode.SAFE, { this }) {

        }
    }
}
