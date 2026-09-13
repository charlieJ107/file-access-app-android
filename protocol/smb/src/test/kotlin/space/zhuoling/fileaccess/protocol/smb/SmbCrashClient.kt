package space.zhuoling.fileaccess.protocol.smb

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.storage.*

/** Separate JVM deliberately exits without closing streams, handles, or the transport. */
object SmbCrashClient {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val config = ConnectionConfig("integration", "Crash fixture", host = "127.0.0.1", port = args[0].toInt(), share = "test")
        val bytes = ByteArray((3 * UPLOAD_CHECKPOINT_BYTES + 71).toInt()) { (it % 251).toByte() }
        SmbStorageProvider().connect(config, Credentials("fileaccess-test", args[1].toCharArray())).use { session ->
            (session as UploadCapability).upload(UploadRequest(args[3], EntryRef("integration", args[2]), "process-death.bin"),
                object : UploadSource {
                    override val length = bytes.size.toLong()
                    override val version = "stable"
                    override fun open() = ByteArrayInputStream(bytes)
                }, { if (it >= args[4].toLong()) Runtime.getRuntime().halt(91) })
        }
        error("The crash boundary was not reached")
    }
}
