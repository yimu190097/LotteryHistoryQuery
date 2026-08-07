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
    val snapshotAt: Long
)
