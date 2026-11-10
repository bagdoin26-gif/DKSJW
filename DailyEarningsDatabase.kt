package com.delivery.callwatch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Local daily earnings storage, independent from the existing CallDatabase.
 *
 * - Keeps the latest 60 days.
 * - Stores the live base delivery count/fee for each date as a replaceable snapshot.
 * - Stores manual promotion/mission additions separately.
 * - Calculates Wednesday-to-Tuesday totals from the saved daily rows.
 *
 * The service/UI must call updateBaseFromScreen() whenever the live "today" values
 * are read, and addExtra()/updateExtra()/deleteExtra() for manual additions.
 */
class DailyEarningsDatabase(context: Context) :
    SQLiteOpenHelper(context, "CallWatchEarnings.db", null, 2) {

    data class DailySummary(
        val date: String,
        val deliveryCount: Int,
        val deliveryFee: Long,
        val promotion: Long,
        val mission: Long
    ) {
        val totalIncome: Long
            get() = deliveryFee + promotion + mission
    }

    data class WeeklySummary(
        val startDate: String,
        val endDate: String,
        val deliveryCount: Int,
        val totalIncome: Long,
        val deliveryFee: Long,
        val promotion: Long,
        val mission: Long
    )

    data class ExtraEntry(
        val id: Long,
        val date: String,
        val type: String,
        val amount: Long,
        val count: Int,
        val memo: String
    )

    companion object {
        const val TYPE_PROMOTION = "프로모션"
        const val TYPE_MISSION = "미션비"
        private const val KEEP_DAYS = 60
        private const val DATE_PATTERN = "yyyy-MM-dd"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE daily_base (
                date TEXT PRIMARY KEY,
                delivery_count INTEGER NOT NULL DEFAULT 0,
                delivery_fee INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE daily_extra (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                type TEXT NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                amount INTEGER NOT NULL,
                memo TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_daily_extra_date ON daily_extra(date)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE daily_extra ADD COLUMN count INTEGER NOT NULL DEFAULT 0")
    }

    @Synchronized
    fun updateBaseFromScreen(date: String, deliveryCount: Int, deliveryFee: Long) {
        val values = ContentValues().apply {
            put("date", date)
            put("delivery_count", deliveryCount.coerceAtLeast(0))
            put("delivery_fee", deliveryFee.coerceAtLeast(0L))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "daily_base", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        deleteOlderThanRetention()
    }

    @Synchronized
    fun addExtra(date: String, type: String, count: Int, amount: Long, memo: String = ""): Long {
        require(type == TYPE_PROMOTION || type == TYPE_MISSION) {
            "type must be $TYPE_PROMOTION or $TYPE_MISSION"
        }
        val values = ContentValues().apply {
            put("date", date)
            put("type", type)
            put("count", count.coerceAtLeast(0))
            put("amount", amount.coerceAtLeast(0L))
            put("memo", memo)
            put("created_at", System.currentTimeMillis())
        }
        val id = writableDatabase.insert("daily_extra", null, values)
        deleteOlderThanRetention()
        return id
    }

    @Synchronized
    fun updateExtra(id: Long, type: String, count: Int, amount: Long, memo: String) {
        require(type == TYPE_PROMOTION || type == TYPE_MISSION)
        val values = ContentValues().apply {
            put("type", type)
            put("count", count.coerceAtLeast(0))
            put("amount", amount.coerceAtLeast(0L))
            put("memo", memo)
        }
        writableDatabase.update("daily_extra", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun deleteExtra(id: Long) {
        writableDatabase.delete("daily_extra", "id=?", arrayOf(id.toString()))
    }

    fun getExtras(date: String): List<ExtraEntry> {
        val result = mutableListOf<ExtraEntry>()
        readableDatabase.query(
            "daily_extra",
            arrayOf("id", "date", "type", "count", "amount", "memo"),
            "date=?",
            arrayOf(date),
            null, null, "id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                result += ExtraEntry(
                    id = c.getLong(0),
                    date = c.getString(1),
                    type = c.getString(2),
                    count = c.getInt(3),
                    amount = c.getLong(4),
                    memo = c.getString(5)
                )
            }
        }
        return result
    }

    fun getDailySummary(date: String): DailySummary {
        var count = 0
        var fee = 0L
        readableDatabase.query(
            "daily_base",
            arrayOf("delivery_count", "delivery_fee"),
            "date=?",
            arrayOf(date),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) {
                count = c.getInt(0)
                fee = c.getLong(1)
            }
        }

        var promotion = 0L
        var mission = 0L
        var extraCount = 0
        readableDatabase.rawQuery(
            """
            SELECT
                COALESCE(SUM(CASE WHEN type=? THEN amount ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN type=? THEN amount ELSE 0 END), 0),
                COALESCE(SUM(count), 0)
            FROM daily_extra WHERE date=?
            """.trimIndent(),
            arrayOf(TYPE_PROMOTION, TYPE_MISSION, date)
        ).use { c ->
            if (c.moveToFirst()) {
                promotion = c.getLong(0)
                mission = c.getLong(1)
                extraCount = c.getInt(2)
            }
        }
        return DailySummary(date, count + extraCount, fee, promotion, mission)
    }

    /** Wednesday through Tuesday, based on the supplied local date. */
    fun getWeeklySummary(anyDateInWeek: String): WeeklySummary {
        val date = parseDate(anyDateInWeek)
        val calendar = Calendar.getInstance().apply {
            time = date
            firstDayOfWeek = Calendar.WEDNESDAY
            minimalDaysInFirstWeek = 1
            while (get(Calendar.DAY_OF_WEEK) != Calendar.WEDNESDAY) {
                add(Calendar.DAY_OF_MONTH, -1)
            }
        }
        val start = formatDate(calendar.time)
        calendar.add(Calendar.DAY_OF_MONTH, 6)
        val end = formatDate(calendar.time)

        var count = 0
        var fee = 0L
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(delivery_count),0),
                   COALESCE(SUM(delivery_fee),0)
            FROM daily_base WHERE date BETWEEN ? AND ?
            """.trimIndent(),
            arrayOf(start, end)
        ).use { c ->
            if (c.moveToFirst()) {
                count = c.getInt(0)
                fee = c.getLong(1)
            }
        }

        var promotion = 0L
        var mission = 0L
        var extraCount = 0
        readableDatabase.rawQuery(
            """
            SELECT
              COALESCE(SUM(CASE WHEN type=? THEN amount ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN type=? THEN amount ELSE 0 END),0),
              COALESCE(SUM(count),0)
            FROM daily_extra WHERE date BETWEEN ? AND ?
            """.trimIndent(),
            arrayOf(TYPE_PROMOTION, TYPE_MISSION, start, end)
        ).use { c ->
            if (c.moveToFirst()) {
                promotion = c.getLong(0)
                mission = c.getLong(1)
                extraCount = c.getInt(2)
            }
        }

        return WeeklySummary(
            start, end, count + extraCount, fee + promotion + mission, fee, promotion, mission
        )
    }

    fun getDailySummaries(days: Int = KEEP_DAYS): List<DailySummary> {
        val dates = mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT date FROM daily_base UNION SELECT date FROM daily_extra ORDER BY date DESC LIMIT ?",
            arrayOf(days.coerceIn(1, KEEP_DAYS).toString())
        ).use { c ->
            while (c.moveToNext()) dates += c.getString(0)
        }
        return dates.sorted().map { getDailySummary(it) }
    }

    @Synchronized
    fun deleteOlderThanRetention() {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -(KEEP_DAYS - 1))
        }
        val cutoff = formatDate(calendar.time)
        writableDatabase.delete("daily_base", "date < ?", arrayOf(cutoff))
        writableDatabase.delete("daily_extra", "date < ?", arrayOf(cutoff))
    }

    private fun parseDate(value: String): Date =
        SimpleDateFormat(DATE_PATTERN, Locale.KOREA).apply {
            isLenient = false
        }.parse(value) ?: Date()

    private fun formatDate(date: Date): String =
        SimpleDateFormat(DATE_PATTERN, Locale.KOREA).format(date)
}
