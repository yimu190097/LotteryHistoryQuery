package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rule_version_catalog")
data class RuleVersionCatalogEntity(
    @PrimaryKey val ruleVersionKey: String,
    val code: String,
    val effectiveFromDate: String,
    val policyLabel: String,
    val changeNote: String,
    val realTiersToUse: Int,
    val prizeTierPairCount: Int,
    val extraFieldCount: Int,
    val appendTierPairCount: Int,
    /** 追加投注比例（仅大乐透等有追加玩法的彩种使用），默认 0.8（80%）。
     *  2007-2019: 0.6（60%）；2019至今: 0.8（80%）。 */
    val appendRatio: Double = 0.8,
    val snapshotAt: Long
)
