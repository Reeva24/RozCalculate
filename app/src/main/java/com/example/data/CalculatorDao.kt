package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CalculatorDao {
    @Query("SELECT * FROM calculator_state WHERE id = 0")
    suspend fun getCalculatorState(): CalculatorState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCalculatorState(state: CalculatorState)

    @Query("SELECT * FROM saved_calculations ORDER BY timestamp DESC")
    fun getAllSavedCalculations(): Flow<List<SavedCalculation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedCalculation(calc: SavedCalculation)

    @Query("DELETE FROM saved_calculations WHERE id = :id")
    suspend fun deleteSavedCalculationById(id: Int)

    @Query("DELETE FROM saved_calculations")
    suspend fun clearSavedCalculations()
}
