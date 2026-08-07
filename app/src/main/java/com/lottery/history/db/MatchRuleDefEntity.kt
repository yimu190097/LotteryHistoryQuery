package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "match_rule_def", primaryKeys = ["ruleVersionKey", "dedupIndex", "ruleIndex"])
data class MatchRuleDefEntity(
    val ruleVersionKey: String,
    val dedupIndex: Int,
    val ruleIndex: Int,
    val matchPrimary: Int,
    val matchSecondary: Int,
    val description: String,
    val prizeName: String,
    val fixedAmountYuan: Long? = null,
    val conditionalKey: String? = null
)
