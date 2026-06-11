package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calculator_state")
data class CalculatorState(
    @PrimaryKey val id: Int = 0,
    val currentDisplay: String = "0",
    val memoryValue: Double = 0.0,
    val grandTotalValue: Double = 0.0,
    val historyJson: String = "[]",
    val lastInputWasOperator: Boolean = false,
    val pendingOperator: String = "",
    val firstOperand: Double = 0.0,
    val isFreshSession: Boolean = true
)
