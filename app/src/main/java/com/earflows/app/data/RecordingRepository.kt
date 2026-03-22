package com.earflows.app.data

import android.os.Environment
import com.earflows.app.audio.SessionMetadata
import com.earflows.app.util.Constants
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Provides access to recorded sessions stored on disk.
 */
class RecordingRepository {

    private val json = Json { ignoreUnknownKeys = true }

    fun getRecordingsDir(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, Constants.RECORDING_DIR)
    }

    fun listSessions(): List<SessionInfo> {
        val dir = getRecordingsDir()
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { sessionDir ->
                val metaFile = File(sessionDir, "session_metadata.json")
                if (metaFile.exists()) {
                    try {
                        val metadata = json.decodeFromString<SessionMetadata>(metaFile.readText())
                        SessionInfo(
                            dirName = sessionDir.name,
                            path = sessionDir.absolutePath,
                            metadata = metadata
                        )
                    } catch (e: Exception) {
                        null
                    }
                } else null
            }
            ?.sortedByDescending { it.metadata.sessionStart }
            ?: emptyList()
    }
}

data class SessionInfo(
    val dirName: String,
    val path: String,
    val metadata: SessionMetadata
)
