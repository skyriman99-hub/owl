package com.owl.downloader.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 1:1 Reconstruction of Seal's `AppDatabase_Impl` & `VideoInfoDao_Impl`
 * Preserves the exact SQLite DDL extracted from `classes.dex`.
 */
data class CommandTemplate(
    val id: Int,
    val name: String,
    val template: String
)

data class OptionShortcut(
    val id: Long,
    val option: String
)

data class CookieProfile(
    val id: Int,
    val url: String,
    val content: String
)

data class DownloadedVideoInfo(
    val id: Int,
    val videoTitle: String,
    val videoAuthor: String,
    val videoUrl: String,
    val thumbnailUrl: String,
    val videoPath: String,
    val extractor: String = "Unknown"
)

class OwlDatabaseHelper(private val appContext: Context) :
    SQLiteOpenHelper(appContext, "owl_database.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        // Exact DDL from Seal's AppDatabase_Impl
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `CommandTemplate` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `template` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `CookieProfile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `content` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `DownloadedVideoInfo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `videoTitle` TEXT NOT NULL, `videoAuthor` TEXT NOT NULL, `videoUrl` TEXT NOT NULL, `thumbnailUrl` TEXT NOT NULL, `videoPath` TEXT NOT NULL, `extractor` TEXT NOT NULL DEFAULT 'Unknown')"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `OptionShortcut` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `option` TEXT NOT NULL)"
        )

        // Seed default CommandTemplates (`PreferenceUtil.initializeTemplateSample`)
        db.execSQL(
            "INSERT INTO `CommandTemplate` (`name`, `template`) VALUES ('Best Video + Metadata (Default)', '--no-mtime -S \"ext\" --embed-metadata --embed-thumbnail --embed-subs')"
        )
        db.execSQL(
            "INSERT INTO `CommandTemplate` (`name`, `template`) VALUES ('Aria2c High-Speed Multi-Thread', '--downloader aria2c --external-downloader-args aria2c:\"-x 16 -s 16 -k 1M --summary-interval=1\" --no-mtime')"
        )
        db.execSQL(
            "INSERT INTO `CommandTemplate` (`name`, `template`) VALUES ('Audio Archival (MP3 + Cover Crop)', '-x --audio-format mp3 --audio-quality 0 --embed-metadata --embed-thumbnail')"
        )
        db.execSQL(
            "INSERT INTO `CommandTemplate` (`name`, `template`) VALUES ('SponsorBlock Cut + Split Chapters', '--sponsorblock-remove default --split-chapters --embed-chapters --merge-output-format mkv')"
        )

        // Seed default OptionShortcuts
        val defaultShortcuts = listOf(
            "--no-mtime",
            "-S \"ext\"",
            "--embed-metadata",
            "--embed-subs",
            "--embed-thumbnail",
            "--split-chapters",
            "--sponsorblock-remove default",
            "--downloader aria2c",
            "--write-auto-subs",
            "--restrict-filenames"
        )
        for (sc in defaultShortcuts) {
            db.execSQL("INSERT INTO `OptionShortcut` (`option`) VALUES (?)", arrayOf<Any>(sc))
        }

        // Seed initial CookieProfile
        db.execSQL(
            "INSERT INTO `CookieProfile` (`url`, `content`) VALUES (?, ?)",
            arrayOf<Any>(
                "https://www.youtube.com",
                "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t1800000000\tPREF\tf6=40000000&hl=ar"
            )
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE `DownloadedVideoInfo` ADD COLUMN `extractor` TEXT NOT NULL DEFAULT 'Unknown'")
            }
        }
        if (oldVersion < 4) {
            // Clear old demo/fallback entries so only real downloaded videos appear
            runCatching {
                db.execSQL("DELETE FROM `DownloadedVideoInfo`")
            }
        }
    }

    fun getAllTemplates(): List<CommandTemplate> {
        val list = mutableListOf<CommandTemplate>()
        readableDatabase.rawQuery("SELECT * FROM CommandTemplate ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    CommandTemplate(
                        id = c.getInt(0),
                        name = c.getString(1),
                        template = c.getString(2)
                    )
                )
            }
        }
        return list
    }

    fun insertTemplate(name: String, template: String) {
        writableDatabase.execSQL(
            "INSERT INTO `CommandTemplate` (`name`, `template`) VALUES (?, ?)",
            arrayOf<Any>(name, template)
        )
    }

    fun updateTemplate(id: Int, name: String, template: String) {
        writableDatabase.execSQL(
            "UPDATE OR ABORT `CommandTemplate` SET `name` = ?, `template` = ? WHERE `id` = ?",
            arrayOf<Any>(name, template, id)
        )
    }

    fun deleteTemplate(id: Int) {
        writableDatabase.execSQL("DELETE FROM `CommandTemplate` WHERE `id` = ?", arrayOf<Any>(id))
    }

    fun getAllShortcuts(): List<OptionShortcut> {
        val list = mutableListOf<OptionShortcut>()
        readableDatabase.rawQuery("SELECT * FROM OptionShortcut ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                list.add(OptionShortcut(id = c.getLong(0), option = c.getString(1)))
            }
        }
        return list
    }

    fun insertShortcut(option: String) {
        writableDatabase.execSQL("INSERT INTO `OptionShortcut` (`option`) VALUES (?)", arrayOf<Any>(option))
    }

    fun deleteShortcut(id: Long) {
        writableDatabase.execSQL("DELETE FROM `OptionShortcut` WHERE `id` = ?", arrayOf<Any>(id))
    }

    fun getAllCookies(): List<CookieProfile> {
        val list = mutableListOf<CookieProfile>()
        readableDatabase.rawQuery("SELECT * FROM CookieProfile ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    CookieProfile(
                        id = c.getInt(0),
                        url = c.getString(1),
                        content = c.getString(2)
                    )
                )
            }
        }
        return list
    }

    fun insertCookie(url: String, content: String) {
        writableDatabase.execSQL(
            "INSERT INTO `CookieProfile` (`url`, `content`) VALUES (?, ?)",
            arrayOf<Any>(url, content)
        )
    }

    fun deleteCookie(id: Int) {
        writableDatabase.execSQL("DELETE FROM `CookieProfile` WHERE `id` = ?", arrayOf<Any>(id))
    }

    fun getAllDownloadedVideos(): List<DownloadedVideoInfo> {
        val list = mutableListOf<DownloadedVideoInfo>()
        readableDatabase.rawQuery("SELECT * FROM DownloadedVideoInfo ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    DownloadedVideoInfo(
                        id = c.getInt(0),
                        videoTitle = c.getString(1),
                        videoAuthor = c.getString(2),
                        videoUrl = c.getString(3),
                        thumbnailUrl = c.getString(4),
                        videoPath = c.getString(5),
                        extractor = c.getString(6)
                    )
                )
            }
        }
        return list
    }

    fun getVideoById(id: Int): DownloadedVideoInfo? {
        readableDatabase.rawQuery(
            "SELECT * FROM DownloadedVideoInfo WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return DownloadedVideoInfo(
                    id = c.getInt(0),
                    videoTitle = c.getString(1),
                    videoAuthor = c.getString(2),
                    videoUrl = c.getString(3),
                    thumbnailUrl = c.getString(4),
                    videoPath = c.getString(5),
                    extractor = c.getString(6)
                )
            }
        }
        return null
    }

    fun updateVideoPath(id: Int, newPath: String) {
        writableDatabase.execSQL(
            "UPDATE `DownloadedVideoInfo` SET `videoPath` = ? WHERE `id` = ?",
            arrayOf<Any>(newPath, id)
        )
    }

    fun insertDownloadedVideo(info: DownloadedVideoInfo): Int {
        writableDatabase.execSQL(
            "INSERT INTO `DownloadedVideoInfo` (`videoTitle`, `videoAuthor`, `videoUrl`, `thumbnailUrl`, `videoPath`, `extractor`) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                info.videoTitle,
                info.videoAuthor,
                info.videoUrl,
                info.thumbnailUrl,
                info.videoPath,
                info.extractor
            )
        )
        readableDatabase.rawQuery("SELECT last_insert_rowid()", null).use { c ->
            if (c.moveToFirst()) {
                return c.getInt(0)
            }
        }
        return 0
    }

    fun deleteDownloadedVideo(id: Int) {
        writableDatabase.execSQL("DELETE FROM `DownloadedVideoInfo` WHERE `id` = ?", arrayOf<Any>(id))
    }
}
