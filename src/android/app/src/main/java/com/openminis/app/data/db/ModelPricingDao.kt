package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ModelPricingDao {
    @Query("SELECT * FROM model_pricing")
    suspend fun getAll(): List<ModelPricingEntity>

    @Query("SELECT * FROM model_pricing WHERE model_id = :modelId LIMIT 1")
    suspend fun getByModelId(modelId: String): ModelPricingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ModelPricingEntity)

    @Update
    suspend fun update(entity: ModelPricingEntity)

    @Query("DELETE FROM model_pricing WHERE model_id = :modelId")
    suspend fun deleteByModelId(modelId: String)
}
