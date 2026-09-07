package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-model token pricing in USD per million tokens.
 *
 * Populated by the user on the Usage Stats screen. When no row exists for a
 * model, cost display degrades gracefully (shows "—" / 0.00) rather than
 * erroring.
 */
@Entity(tableName = "model_pricing")
data class ModelPricingEntity(
    @PrimaryKey
    @ColumnInfo(name = "model_id")
    val modelId: String,
    /** USD per million input tokens (including cache read / write). */
    @ColumnInfo(name = "input_per_million")
    val inputPerMillion: Double = 0.0,
    /** USD per million output tokens. */
    @ColumnInfo(name = "output_per_million")
    val outputPerMillion: Double = 0.0,
    /** USD per million cache-read tokens. */
    @ColumnInfo(name = "cache_read_per_million")
    val cacheReadPerMillion: Double = 0.0,
    /** USD per million cache-write (creation) tokens. */
    @ColumnInfo(name = "cache_write_per_million")
    val cacheWritePerMillion: Double = 0.0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
