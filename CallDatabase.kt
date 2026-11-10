
package com.delivery.callwatch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CallDatabase(context: Context) :
    SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

    data class CallRecord(
        val id: Long,
        val dispatchId: String,
        val acceptButtonId: String,
        val rejectButtonId: String,
        val amount: String,
        val pickup: String,
        val destination: String,
        val distance: String,
        val rawText: String,
        val status: String,
        val capturedAt: Long,
        val processedAt: Long
    )

    companion object {
        private const val DATABASE_NAME = "CallWatchCalls.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_CALLS = "calls"

        const val STATUS_WAITING = "대기"
        const val STATUS_ACCEPTED = "수락"
        const val STATUS_REJECTED = "거절"

        const val RETENTION_MS = 30L * 60L * 1000L

        private const val DUPLICATE_WINDOW_MS = 60_000L
    }

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE $TABLE_CALLS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dispatchId TEXT NOT NULL DEFAULT '',
                acceptButtonId TEXT NOT NULL DEFAULT '',
                rejectButtonId TEXT NOT NULL DEFAULT '',
                amount TEXT NOT NULL DEFAULT '',
                pickup TEXT NOT NULL DEFAULT '',
                destination TEXT NOT NULL DEFAULT '',
                distance TEXT NOT NULL DEFAULT '',
                rawText TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT '$STATUS_WAITING',
                capturedAt INTEGER NOT NULL,
                processedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX index_calls_capturedAt
            ON $TABLE_CALLS(capturedAt)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX index_calls_status
            ON $TABLE_CALLS(status)
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            // 기존 v1 테이블 구조가 동일하므로 유지한다.
        }
    }

    private fun amountNumber(amount: String): Long {
        return amount
            .replace(",", "")
            .replace("원", "")
            .trim()
            .toLongOrNull() ?: 0L
    }

    /**
     * 대기콜 저장
     * - 동일 콜 중복 저장 방지
     * - 대기콜 최대 2건 유지
     * - 금액순 조회는 getCalls()에서 처리
     */
    @Synchronized
    fun saveCall(
        dispatchId: String,
        acceptButtonId: String,
        rejectButtonId: String,
        amount: String,
        pickup: String,
        destination: String,
        distance: String,
        rawText: String
    ): Long {

        val db = writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()

        try {
            deleteExpiredInternal(db, now)

            val fingerprint = dispatchId.ifBlank {
                listOf(
                    amount.trim(),
                    pickup.trim(),
                    destination.trim(),
                    distance.trim()
                ).joinToString("|")
            }

            val duplicateCursor = db.rawQuery(
                """
                SELECT id
                FROM $TABLE_CALLS
                WHERE status = ?
                  AND capturedAt >= ?
                  AND (
                    dispatchId = ?
                    OR (
                        dispatchId = ''
                        AND rawText = ?
                    )
                  )
                ORDER BY capturedAt DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    STATUS_WAITING,
                    (now - DUPLICATE_WINDOW_MS).toString(),
                    fingerprint,
                    rawText
                )
            )

            val isDuplicate = duplicateCursor.use {
                it.moveToFirst()
            }

            if (isDuplicate) {
                db.setTransactionSuccessful()
                return -1L
            }

            val values = ContentValues().apply {
                put("dispatchId", fingerprint)
                put("acceptButtonId", acceptButtonId)
                put("rejectButtonId", rejectButtonId)
                put("amount", amount)
                put("pickup", pickup)
                put("destination", destination)
                put("distance", distance)
                put("rawText", rawText)
                put("status", STATUS_WAITING)
                put("capturedAt", now)
                put("processedAt", 0L)
            }

            val insertedId = db.insert(
                TABLE_CALLS,
                null,
                values
            )

            db.setTransactionSuccessful()

            return insertedId

        } finally {
            db.endTransaction()
        }
    }

    /**
     * 특정 기록의 상태 변경
     */
    @Synchronized
    fun updateStatus(
        id: Long,
        status: String
    ): Int {

        if (
            status != STATUS_ACCEPTED &&
            status != STATUS_REJECTED
        ) {
            return 0
        }

        val values = ContentValues().apply {
            put("status", status)
            put("processedAt", System.currentTimeMillis())
        }

        return writableDatabase.update(
            TABLE_CALLS,
            values,
            "id = ? AND status = ?",
            arrayOf(
                id.toString(),
                STATUS_WAITING
            )
        )
    }

    /**
     * 가장 최근 대기콜 상태 변경
     */
    @Synchronized
    fun updateLatestWaitingStatus(status: String): Int {

        if (
            status != STATUS_ACCEPTED &&
            status != STATUS_REJECTED
        ) {
            return 0
        }

        val db = writableDatabase

        val cursor = db.rawQuery(
            """
            SELECT id
            FROM $TABLE_CALLS
            WHERE status = ?
            ORDER BY capturedAt DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(STATUS_WAITING)
        )

        val id = cursor.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }

        if (id == -1L) return 0

        return updateStatus(id, status)
    }

    /**
     * 전체 기록 조회
     * 금액 높은 순으로 정렬
     */
    @Synchronized
    fun getCalls(): List<CallRecord> {

        val db = writableDatabase

        deleteExpiredInternal(
            db,
            System.currentTimeMillis()
        )

        val result = mutableListOf<CallRecord>()

        val cursor = db.query(
            TABLE_CALLS,
            arrayOf(
                "id",
                "dispatchId",
                "acceptButtonId",
                "rejectButtonId",
                "amount",
                "pickup",
                "destination",
                "distance",
                "rawText",
                "status",
                "capturedAt",
                "processedAt"
            ),
            null,
            null,
            null,
            null,
            "capturedAt DESC"
        )

        cursor.use {
            while (it.moveToNext()) {

                result.add(
                    CallRecord(
                        id = it.getLong(0),
                        dispatchId = it.getString(1) ?: "",
                        acceptButtonId = it.getString(2) ?: "",
                        rejectButtonId = it.getString(3) ?: "",
                        amount = it.getString(4) ?: "",
                        pickup = it.getString(5) ?: "",
                        destination = it.getString(6) ?: "",
                        distance = it.getString(7) ?: "",
                        rawText = it.getString(8) ?: "",
                        status = it.getString(9) ?: STATUS_WAITING,
                        capturedAt = it.getLong(10),
                        processedAt = it.getLong(11)
                    )
                )
            }
        }

        return result.sortedByDescending {
            amountNumber(it.amount)
        }
    }

    /**
     * 현재 대기 중인 콜만 조회
     */
    @Synchronized
    fun getWaitingCalls(): List<CallRecord> {
        return getCalls()
            .filter { it.status == STATUS_WAITING }
    }

    /**
     * 30분 지난 기록 삭제
     */
    @Synchronized
    fun deleteExpired(): Int {

        return deleteExpiredInternal(
            writableDatabase,
            System.currentTimeMillis()
        )
    }

    private fun deleteExpiredInternal(
        db: SQLiteDatabase,
        now: Long
    ): Int {

        val expirationTime = now - RETENTION_MS

        return db.delete(
            TABLE_CALLS,
            "capturedAt <= ?",
            arrayOf(expirationTime.toString())
        )
    }

    /**
     * 전체 기록 삭제
     */
    @Synchronized
    fun deleteAll(): Int {
        return writableDatabase.delete(
            TABLE_CALLS,
            null,
            null
        )
    }
}
