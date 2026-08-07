package com.lottery.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MatchRuleDefDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MatchRuleDefEntity>)

    @Query("SELECT * FROM match_rule_def WHERE ruleVersionKey = :ruleVersionKey ORDER BY dedupIndex, ruleIndex")
    suspend fun getByRuleVersion(ruleVersionKey: String): List<MatchRuleDefEntity>

    @Query("DELETE FROM match_rule_def WHERE ruleVersionKey IN (SELECT ruleVersionKey FROM rule_version_catalog WHERE code = :code)")
    suspend fun clearAllForCode(code: String)
}
