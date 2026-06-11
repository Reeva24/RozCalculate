package com.example.data

import kotlinx.coroutines.flow.Flow

class CalculatorRepository(private val calculatorDao: CalculatorDao) {
    val allSavedCalculations: Flow<List<SavedCalculation>> = calculatorDao.getAllSavedCalculations()

    suspend fun getCalculatorState(): CalculatorState? {
        return calculatorDao.getCalculatorState()
    }

    suspend fun saveCalculatorState(state: CalculatorState) {
        calculatorDao.saveCalculatorState(state)
    }

    suspend fun insertSavedCalculation(calc: SavedCalculation) {
        calculatorDao.insertSavedCalculation(calc)
    }

    suspend fun deleteSavedCalculationById(id: Int) {
        calculatorDao.deleteSavedCalculationById(id)
    }

    suspend fun clearSavedCalculations() {
        calculatorDao.clearSavedCalculations()
    }
}
