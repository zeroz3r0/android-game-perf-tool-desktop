package com.gameperf.desktop.cloud

import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileReader

/**
 * Google Drive integration for GamePerf session pack sync.
 *
 * ## Setup (one-time per machine)
 * 1. Go to https://console.cloud.google.com
 * 2. Create a project → enable "Google Drive API"
 * 3. Credentials → Create OAuth2 client ID → Desktop application
 * 4. Download JSON → save as ~/.gameperf/credentials.json
 * 5. In the app: click "Conectar Google Drive" → browser opens → authorize
 *
 * ## Team sharing
 * - First member connects → creates "GamePerf Sessions" folder automatically
 * - Shares that folder with teammates via Google Drive
 * - Teammates paste the shared folder ID in app Settings → Drive folder
 *
 * ## Scope used
 * DriveScopes.DRIVE — needed to read/write to folders shared by other users.
 */
class DriveSync(private val configDir: File) {

    companion object {
        private const val APP_NAME        = "GamePerf Desktop"
        private const val FOLDER_NAME     = "GamePerf Sessions"
        private const val MIME_FOLDER     = "application/vnd.google-apps.folder"
        private const val MIME_PACK       = "application/zip"
        private const val CONFIG_FILE     = "drive-config.json"
        private const val CREDENTIALS_FILE = "credentials.json"
    }

    private val jsonFactory   = GsonFactory.getDefaultInstance()
    private val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
    private val scopes        = listOf(DriveScopes.DRIVE)

    private val credentialsFile get() = File(configDir, CREDENTIALS_FILE)
    private val tokensDir       get() = File(configDir, "tokens")
    private val configFile      get() = File(configDir, CONFIG_FILE)

    private val configJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class DriveConfig(
        val teamFolderId: String = "",
        val userEmail: String = "",
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Public data
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Info about a session stored in Drive — built from file appProperties so
     * we don't need to download the pack to show the list.
     */
    data class RemoteSession(
        val fileId: String,
        val fileName: String,
        val sessionId: String,
        val sessionName: String,
        val grade: Char,
        val avgFps: Int,
        val deviceModel: String,
        val gameShort: String,
        val durationSec: Int,
        val date: String,
        val score: Int,
        val tag: String,
        val uploaderEmail: String,
        val modifiedTime: String,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // State queries
    // ─────────────────────────────────────────────────────────────────────────

    /** True if credentials.json is present on disk. */
    val hasCredentials: Boolean get() = credentialsFile.exists()

    /** True if OAuth tokens are stored (user has previously authorized). */
    val isAuthenticated: Boolean
        get() = tokensDir.exists() && tokensDir.listFiles()?.isNotEmpty() == true

    /** Configured team folder ID (may be empty = not set yet). */
    val teamFolderId: String
        get() = loadConfig().teamFolderId

    /** Email of the authenticated user (empty if not authenticated). */
    val userEmail: String
        get() = loadConfig().userEmail

    // ─────────────────────────────────────────────────────────────────────────
    // Authentication
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run the OAuth2 browser flow. Opens the default browser, waits for the
     * user to authorize, saves tokens, then resolves the team folder.
     * Returns the authenticated user email on success.
     * Throws on failure (credentials not found, user cancelled, etc.).
     */
    fun authenticate(): String {
        val secrets = loadClientSecrets()
        val flow = GoogleAuthorizationCodeFlow.Builder(
            httpTransport, jsonFactory, secrets, scopes
        )
            .setDataStoreFactory(FileDataStoreFactory(tokensDir))
            .setAccessType("offline")
            .build()

        val receiver = LocalServerReceiver.Builder().setPort(8882).build()
        val credential = AuthorizationCodeInstalledApp(flow, receiver).authorize("user")

        // Fetch user email via Drive about endpoint
        val drive = buildDrive(credential)
        val about = drive.about().get().setFields("user/emailAddress").execute()
        val email = about.user?.emailAddress ?: "unknown@gmail.com"

        // Ensure team folder exists
        val folderId = ensureTeamFolder(drive)
        saveConfig(DriveConfig(teamFolderId = folderId, userEmail = email))

        return email
    }

    /** Remove stored tokens — next call to authenticate() will require browser flow. */
    fun signOut() {
        tokensDir.deleteRecursively()
        saveConfig(DriveConfig())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upload a session pack to the team folder.
     * [appProps] should be [SessionPack.appPropertiesFrom] result.
     * Returns the new Drive file ID.
     */
    fun uploadSession(packFile: File, appProps: Map<String, String>): String {
        val drive = buildAuthenticatedDrive()
        val folderId = requireFolderId()

        val meta = DriveFile().apply {
            name = packFile.name
            parents = listOf(folderId)
            appProperties = appProps
        }
        val content = FileContent(MIME_PACK, packFile)
        val created = drive.files().create(meta, content)
            .setFields("id, name")
            .execute()
        return created.id
    }

    /**
     * List all `.gameperf` files in the team folder.
     * Reads only metadata (appProperties) — no file downloads.
     */
    fun listSessions(): List<RemoteSession> {
        val drive = buildAuthenticatedDrive()
        val folderId = requireFolderId()

        val fields = "files(id,name,modifiedTime,owners,appProperties)"
        val result = drive.files().list()
            .setQ("'$folderId' in parents and trashed=false")
            .setFields(fields)
            .setOrderBy("modifiedTime desc")
            .setPageSize(100)
            .execute()

        return result.files.mapNotNull { f ->
            val p = f.appProperties ?: return@mapNotNull null
            if (p["gameperf_version"] == null) return@mapNotNull null
            RemoteSession(
                fileId       = f.id,
                fileName     = f.name ?: "",
                sessionId    = p["session_id"] ?: "",
                sessionName  = p["session_name"] ?: f.name ?: "",
                grade        = p["grade"]?.firstOrNull() ?: '?',
                avgFps       = p["avg_fps"]?.toIntOrNull() ?: 0,
                deviceModel  = p["device"] ?: "",
                gameShort    = p["game"] ?: "",
                durationSec  = p["duration_s"]?.toIntOrNull() ?: 0,
                date         = p["date"] ?: "",
                score        = p["score"]?.toIntOrNull() ?: 0,
                tag          = p["tag"] ?: "OUR_GAME",
                uploaderEmail = f.owners?.firstOrNull()?.emailAddress ?: "",
                modifiedTime = f.modifiedTime?.toString() ?: "",
            )
        }
    }

    /**
     * Download a session pack to [destDir].
     * Returns the downloaded file.
     */
    fun downloadSession(fileId: String, destDir: File): File {
        val drive = buildAuthenticatedDrive()
        destDir.mkdirs()

        // Get file name first
        val meta = drive.files().get(fileId).setFields("name").execute()
        val dest = File(destDir, meta.name ?: "session_$fileId.gameperf")

        drive.files().get(fileId).executeMediaAndDownloadTo(dest.outputStream())
        return dest
    }

    /** Delete a session pack from Drive. */
    fun deleteRemoteSession(fileId: String) {
        buildAuthenticatedDrive().files().delete(fileId).execute()
    }

    /**
     * Override the team folder ID (for when a teammate shares their folder).
     * The folder ID is the last segment of the Drive URL:
     * https://drive.google.com/drive/folders/<FOLDER_ID>
     */
    fun setTeamFolderId(folderId: String) {
        saveConfig(loadConfig().copy(teamFolderId = folderId.trim()))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadClientSecrets(): GoogleClientSecrets {
        check(credentialsFile.exists()) {
            "credentials.json not found at ${credentialsFile.absolutePath}. " +
                "See setup instructions in the app."
        }
        return GoogleClientSecrets.load(jsonFactory, FileReader(credentialsFile))
    }

    private fun buildAuthenticatedDrive(): Drive {
        check(isAuthenticated) { "Not authenticated. Call authenticate() first." }
        val secrets = loadClientSecrets()
        val flow = GoogleAuthorizationCodeFlow.Builder(
            httpTransport, jsonFactory, secrets, scopes
        )
            .setDataStoreFactory(FileDataStoreFactory(tokensDir))
            .setAccessType("offline")
            .build()

        val credential = flow.loadCredential("user")
            ?: error("Stored credential not found — please re-authenticate.")
        return buildDrive(credential)
    }

    private fun buildDrive(credential: com.google.api.client.auth.oauth2.Credential): Drive =
        Drive.Builder(httpTransport, jsonFactory, credential)
            .setApplicationName(APP_NAME)
            .build()

    private fun ensureTeamFolder(drive: Drive): String {
        // Check if folder already exists to avoid duplicates
        val existing = drive.files().list()
            .setQ("mimeType='$MIME_FOLDER' and name='$FOLDER_NAME' and trashed=false")
            .setFields("files(id,name)")
            .execute()
            .files
            .firstOrNull()

        if (existing != null) return existing.id

        // Create it
        val folder = DriveFile().apply {
            name = FOLDER_NAME
            mimeType = MIME_FOLDER
        }
        return drive.files().create(folder).setFields("id").execute().id
    }

    private fun requireFolderId(): String {
        val id = teamFolderId
        check(id.isNotEmpty()) { "Team folder ID not configured." }
        return id
    }

    private fun loadConfig(): DriveConfig {
        if (!configFile.exists()) return DriveConfig()
        return try {
            configJson.decodeFromString(DriveConfig.serializer(), configFile.readText())
        } catch (_: Exception) {
            DriveConfig()
        }
    }

    private fun saveConfig(config: DriveConfig) {
        configDir.mkdirs()
        configFile.writeText(configJson.encodeToString(DriveConfig.serializer(), config))
    }
}
