package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Standalone Room database for provider config. Lives in `provider.db`,
 * separate from `minis.db` (sessions/messages/etc), so that downgrading
 * to a version that doesn't know about these tables does NOT crash on
 * `minis.db`. Old builds simply ignore provider.db and continue to read
 * provider config from the legacy SharedPreferences JSON mirror — which
 * we keep writing on every save so it's never stale.
 *
 * On re-upgrade, ProviderRepository compares a stored hash of the JSON
 * mirror against the live mirror to detect "old build wrote JSON behind
 * our back during the downgrade window" and re-imports if needed; that
 * way provider.db can never become authoritative-but-stale relative to
 * what the user did while downgraded.
 *
 * Schema starts at version 1; future column adds use Migration like
 * AppDatabase does. We deliberately do NOT enable
 * fallbackToDestructiveMigration: provider.db is the only copy of
 * structured provider state, and the JSON mirror is the safety net, not
 * a substitute for proper migrations.
 */
@Database(
    entities = [
        ProviderInstanceEntity::class,
        ProviderModelEntryEntity::class,
        ProviderModelGroupEntity::class,
        ProviderAgentLoopIdEntity::class,
        ProviderConfigMetaEntity::class,
        ProviderThinkingRuleEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class ProviderDatabase : RoomDatabase() {
    abstract fun providerConfigDao(): ProviderConfigDao

    companion object {
        @Volatile
        private var INSTANCE: ProviderDatabase? = null

        /**
         * [T-android-azure-openai] Add the Azure OpenAI mode column. Pure
         * additive ALTER with NOT NULL DEFAULT 0 so every existing provider row
         * backfills to "off" — no data rewrite, no provider drop. This is the
         * first migration on provider.db (introduced at v1 with all columns
         * inline); older builds that don't know the column keep reading the JSON
         * mirror, and re-upgrade re-imports if they wrote behind our back.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN azure_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * [GH#68 T-android-image-endpoint-persist] Add the image-endpoint
         * picker columns that the Room migration of the provider store
         * (4dd24ecf) missed — the JSON model carried them but every Room
         * round-trip dropped the value, snapping the picker back to Auto.
         * Pure additive nullable TEXT ALTERs; existing rows read as null →
         * auto / no cached probe, no data rewrite, no provider drop.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_mode TEXT")
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_resolved TEXT")
            }
        }

        /**
         * [T-android-thinking-rules-phase2] Add the custom-thinking-rules table.
         * Pure additive CREATE TABLE — no existing row is touched, and a provider with
         * no custom rules has an empty table, so resolution stays byte-identical to
         * pre-migration (the resolver prepends an empty list above the built-ins).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_thinking_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        provider_instance_id TEXT NOT NULL,
                        label TEXT NOT NULL,
                        scope_kind TEXT NOT NULL,
                        scope_pattern TEXT,
                        wire_format_json TEXT,
                        reasoning_echo_json TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_provider_thinking_rules_provider_instance_id " +
                        "ON provider_thinking_rules(provider_instance_id)",
                )
            }
        }


        fun getInstance(context: Context): ProviderDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProviderDatabase::class.java,
                    "provider.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
