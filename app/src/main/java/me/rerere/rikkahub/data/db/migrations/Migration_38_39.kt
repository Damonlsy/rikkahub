package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Existing versions allowed duplicate dates. Keep the latest row before enforcing one row per day.
        db.execSQL(
            "DELETE FROM period_records WHERE id NOT IN " +
                "(SELECT MAX(id) FROM period_records GROUP BY date)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_period_records_date " +
                "ON period_records(date)"
        )
    }
}
