package com.lottery.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RuleVersionCatalogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<RuleVersionCatalogEntity>)

    @Query("SELECT * FROM rule_version_catalog ORDER BY effectiveFromDate DESC")
    suspend fun getAll(): List<RuleVersionCatalogEntity>

    @Query("SELECT * FROM rule_version_catalog WHERE ruleVersionKey = :key LIMIT 1")
    suspend fun getByKey(key: String): RuleVersionCatalogEntity?
}
