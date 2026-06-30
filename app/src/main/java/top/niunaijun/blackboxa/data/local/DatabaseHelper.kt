package top.niunaijun.blackboxa.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * DatabaseHelper — Persistent SQLite storage for game metadata and install status.
 *
 * Tracks:
 * - Game catalog metadata (id, title, url, arch, icon)
 * - Download/install state per game
 * - Installation timestamp for "already installed" checks
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "games_console.db"
        private const val DB_VERSION = 2

        // Table
        const val TABLE_GAMES = "games"

        // Columns
        const val COL_GAME_ID = "game_id"
        const val COL_TITLE = "title"
        const val COL_DOWNLOAD_URL = "download_url"
        const val COL_SHA256 = "sha256"
        const val COL_API_LEVEL = "api_level"
        const val COL_ARCH_TYPE = "architecture_type"
        const val COL_CONTROL_TYPE = "control_type"
        const val COL_OBB_URL = "obb_url"
        const val COL_ICON_URL = "icon_url"
        const val COL_INSTALL_STATUS = "install_status"
        const val COL_INSTALLED_AT = "installed_at"
        const val COL_PACKAGE_NAME = "package_name"

        // Install status constants
        const val STATUS_NOT_INSTALLED = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_DOWNLOADED = 2
        const val STATUS_EXTRACTING = 3
        const val STATUS_INSTALLING = 4
        const val STATUS_INSTALLED = 5
        const val STATUS_FAILED = 6

        @Volatile
        private var instance: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_GAMES (
                $COL_GAME_ID TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_DOWNLOAD_URL TEXT NOT NULL,
                $COL_SHA256 TEXT DEFAULT '',
                $COL_API_LEVEL INTEGER DEFAULT 10,
                $COL_ARCH_TYPE TEXT DEFAULT '32bit',
                $COL_CONTROL_TYPE TEXT DEFAULT 'touch',
                $COL_OBB_URL TEXT DEFAULT '',
                $COL_ICON_URL TEXT DEFAULT '',
                $COL_INSTALL_STATUS INTEGER DEFAULT $STATUS_NOT_INSTALLED,
                $COL_INSTALLED_AT INTEGER DEFAULT 0,
                $COL_PACKAGE_NAME TEXT DEFAULT ''
            )
        """.trimIndent()
        db.execSQL(createTable)
        Log.d(TAG, "Database created: $DB_NAME v$DB_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_GAMES ADD COLUMN $COL_ICON_URL TEXT DEFAULT ''")
                Log.d(TAG, "Database upgraded: added icon_url column")
            } catch (e: Exception) {
                Log.w(TAG, "Column already exists or upgrade failed: ${e.message}")
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Upsert (insert or update) game metadata
    // ──────────────────────────────────────────────────

    fun upsertGame(game: GameEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_GAME_ID, game.gameId)
            put(COL_TITLE, game.title)
            put(COL_DOWNLOAD_URL, game.downloadUrl)
            put(COL_SHA256, game.sha256)
            put(COL_API_LEVEL, game.apiLevel)
            put(COL_ARCH_TYPE, game.architectureType)
            put(COL_CONTROL_TYPE, game.controlType)
            put(COL_OBB_URL, game.obbUrl)
            put(COL_ICON_URL, game.iconUrl)
            put(COL_INSTALL_STATUS, game.installStatus)
            put(COL_INSTALLED_AT, game.installedAt)
            put(COL_PACKAGE_NAME, game.packageName)
        }
        val rowsAffected = db.insertWithOnConflict(
            TABLE_GAMES, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        Log.d(TAG, "Upserted game ${game.gameId}: $rowsAffected rows")
    }

    fun upsertGames(games: List<GameEntity>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (game in games) {
                val values = ContentValues().apply {
                    put(COL_GAME_ID, game.gameId)
                    put(COL_TITLE, game.title)
                    put(COL_DOWNLOAD_URL, game.downloadUrl)
                    put(COL_SHA256, game.sha256)
                    put(COL_API_LEVEL, game.apiLevel)
                    put(COL_ARCH_TYPE, game.architectureType)
                    put(COL_CONTROL_TYPE, game.controlType)
                    put(COL_OBB_URL, game.obbUrl)
                    put(COL_ICON_URL, game.iconUrl)
                    put(COL_INSTALL_STATUS, game.installStatus)
                    put(COL_INSTALLED_AT, game.installedAt)
                    put(COL_PACKAGE_NAME, game.packageName)
                }
                db.insertWithOnConflict(TABLE_GAMES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            Log.d(TAG, "Upserted ${games.size} games in batch")
        } finally {
            db.endTransaction()
        }
    }

    // ──────────────────────────────────────────────────
    //  Read queries
    // ──────────────────────────────────────────────────

    fun getGame(gameId: String): GameEntity? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_GAMES, null,
            "$COL_GAME_ID = ?", arrayOf(gameId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) it.toGameEntity() else null
        }
    }

    fun getAllGames(): List<GameEntity> {
        val db = readableDatabase
        val cursor = db.query(TABLE_GAMES, null, null, null, null, null, "$COL_TITLE ASC")
        val games = mutableListOf<GameEntity>()
        cursor.use {
            while (it.moveToNext()) {
                games.add(it.toGameEntity())
            }
        }
        return games
    }

    fun getInstalledGames(): List<GameEntity> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_GAMES, null,
            "$COL_INSTALL_STATUS = ?", arrayOf(STATUS_INSTALLED.toString()),
            null, null, "$COL_INSTALLED_AT DESC"
        )
        val games = mutableListOf<GameEntity>()
        cursor.use {
            while (it.moveToNext()) {
                games.add(it.toGameEntity())
            }
        }
        return games
    }

    fun isInstalled(gameId: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_GAMES WHERE $COL_GAME_ID = ? AND $COL_INSTALL_STATUS = ?",
            arrayOf(gameId, STATUS_INSTALLED.toString())
        )
        return cursor.use {
            it.moveToFirst() && it.getInt(0) > 0
        }
    }

    // ──────────────────────────────────────────────────
    //  Status updates
    // ──────────────────────────────────────────────────

    fun updateInstallStatus(gameId: String, status: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_INSTALL_STATUS, status)
            if (status == STATUS_INSTALLED) {
                put(COL_INSTALLED_AT, System.currentTimeMillis())
            }
        }
        db.update(TABLE_GAMES, values, "$COL_GAME_ID = ?", arrayOf(gameId))
        Log.d(TAG, "Updated status for $gameId: $status")
    }

    fun markInstalled(gameId: String, packageName: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_INSTALL_STATUS, STATUS_INSTALLED)
            put(COL_INSTALLED_AT, System.currentTimeMillis())
            put(COL_PACKAGE_NAME, packageName)
        }
        db.update(TABLE_GAMES, values, "$COL_GAME_ID = ?", arrayOf(gameId))
        Log.d(TAG, "Marked $gameId as installed (pkg=$packageName)")
    }

    fun markFailed(gameId: String) {
        updateInstallStatus(gameId, STATUS_FAILED)
    }

    // ──────────────────────────────────────────────────
    //  Delete
    // ──────────────────────────────────────────────────

    fun deleteGame(gameId: String) {
        val db = writableDatabase
        db.delete(TABLE_GAMES, "$COL_GAME_ID = ?", arrayOf(gameId))
        Log.d(TAG, "Deleted game $gameId")
    }

    fun deleteAll() {
        val db = writableDatabase
        db.delete(TABLE_GAMES, null, null)
        Log.d(TAG, "Deleted all games")
    }

    // ──────────────────────────────────────────────────
    //  Cursor → Entity mapping
    // ──────────────────────────────────────────────────

    private fun Cursor.toGameEntity(): GameEntity {
        return GameEntity(
            gameId = getString(getColumnIndexOrThrow(COL_GAME_ID)),
            title = getString(getColumnIndexOrThrow(COL_TITLE)),
            downloadUrl = getString(getColumnIndexOrThrow(COL_DOWNLOAD_URL)),
            sha256 = getString(getColumnIndexOrThrow(COL_SHA256)),
            apiLevel = getInt(getColumnIndexOrThrow(COL_API_LEVEL)),
            architectureType = getString(getColumnIndexOrThrow(COL_ARCH_TYPE)),
            controlType = getString(getColumnIndexOrThrow(COL_CONTROL_TYPE)),
            obbUrl = getString(getColumnIndexOrThrow(COL_OBB_URL)),
            iconUrl = getString(getColumnIndexOrThrow(COL_ICON_URL)),
            installStatus = getInt(getColumnIndexOrThrow(COL_INSTALL_STATUS)),
            installedAt = getLong(getColumnIndexOrThrow(COL_INSTALLED_AT)),
            packageName = getString(getColumnIndexOrThrow(COL_PACKAGE_NAME))
        )
    }

    // ──────────────────────────────────────────────────
    //  Data class for game entity
    // ──────────────────────────────────────────────────

    data class GameEntity(
        val gameId: String,
        val title: String,
        val downloadUrl: String,
        val sha256: String = "",
        val apiLevel: Int = 10,
        val architectureType: String = "32bit",
        val controlType: String = "touch",
        val obbUrl: String = "",
        val iconUrl: String = "",
        val installStatus: Int = STATUS_NOT_INSTALLED,
        val installedAt: Long = 0L,
        val packageName: String = ""
    )
}
