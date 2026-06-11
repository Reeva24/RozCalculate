package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.network.CalculatorResult
import com.example.network.GeminiManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class CalculatorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CalculatorRepository
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listStringAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    private val _uiState = MutableStateFlow(CalculatorUiState())
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()

    // Consecutives click count on MRC helper
    private var mrcClickCount = 0
    private var gtClickCount = 0

    init {
        val database = AppDatabase.getDatabase(application)
        repository = CalculatorRepository(database.calculatorDao())

        // Load active calculations archive
        viewModelScope.launch {
            repository.allSavedCalculations.collect { list ->
                _uiState.update { it.copy(savedCalculations = list) }
            }
        }

        // Restore state
        viewModelScope.launch {
            try {
                val state = repository.getCalculatorState()
                if (state != null) {
                    val stepsList = try {
                        listStringAdapter.fromJson(state.historyJson) ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    _uiState.update {
                        it.copy(
                            display = state.currentDisplay,
                            memory = state.memoryValue,
                            grandTotal = state.grandTotalValue,
                            historyList = stepsList,
                            pendingOperator = state.pendingOperator,
                            previousValue = state.firstOperand,
                            isNewInputExpected = state.lastInputWasOperator,
                            isFreshSession = state.isFreshSession
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("CalculatorViewModel", "Failed to restore calculator state", e)
            }
        }
    }

    private fun saveCurrentState() {
        viewModelScope.launch {
            try {
                val current = _uiState.value
                val stepsJson = listStringAdapter.toJson(current.historyList)
                val stateObj = CalculatorState(
                    id = 0,
                    currentDisplay = current.display,
                    memoryValue = current.memory,
                    grandTotalValue = current.grandTotal,
                    historyJson = stepsJson,
                    lastInputWasOperator = current.isNewInputExpected,
                    pendingOperator = current.pendingOperator,
                    firstOperand = current.previousValue,
                    isFreshSession = current.isFreshSession
                )
                repository.saveCalculatorState(stateObj)
            } catch (e: Exception) {
                Log.e("CalculatorViewModel", "Failed to auto-save state", e)
            }
        }
    }

    // --- Action Methods ---

    fun changeTab(index: Int) {
        _uiState.update { it.copy(activeTab = index) }
    }

    fun updateAiPromptInput(input: String) {
        _uiState.update { it.copy(aiPromptInput = input) }
    }

    fun onDigitClick(digit: String) {
        resetDirectClears()
        _uiState.update { currentState ->
            // If in review check mode
            if (currentState.checkIndex != -1) {
                if (!currentState.isCorrecting) {
                    // Start correcting the step immediately
                    val newHistory = currentState.historyList.toMutableList()
                    newHistory[currentState.checkIndex] = digit
                    currentState.copy(
                        historyList = newHistory,
                        display = digit,
                        isCorrecting = true,
                        isFreshSession = false
                    )
                } else {
                    // Currently correcting
                    val curVal = if (currentState.display == "0") "" else currentState.display
                    val newVal = curVal + digit
                    val newHistory = currentState.historyList.toMutableList()
                    newHistory[currentState.checkIndex] = newVal
                    currentState.copy(
                        display = newVal,
                        historyList = newHistory,
                        isFreshSession = false
                    )
                }
            } else {
                // Standard input mode
                val newVal = if (currentState.isNewInputExpected || currentState.display == "0") {
                    if (digit == "0" || digit == "00") "0" else digit
                } else {
                    if (currentState.display.length < 12) {
                        currentState.display + digit
                    } else {
                        currentState.display // Max 12 digits
                    }
                }
                currentState.copy(
                    display = newVal,
                    isNewInputExpected = false,
                    isFreshSession = false
                )
            }
        }
        saveCurrentState()
    }

    fun onDecimalClick() {
        resetDirectClears()
        _uiState.update { currentState ->
            if (currentState.checkIndex != -1) {
                if (!currentState.isCorrecting) {
                    val newHistory = currentState.historyList.toMutableList()
                    newHistory[currentState.checkIndex] = "0."
                    currentState.copy(
                        historyList = newHistory,
                        display = "0.",
                        isCorrecting = true,
                        isFreshSession = false
                    )
                } else {
                    if (!currentState.display.contains(".")) {
                        val newVal = currentState.display + "."
                        val newHistory = currentState.historyList.toMutableList()
                        newHistory[currentState.checkIndex] = newVal
                        currentState.copy(
                            display = newVal,
                            historyList = newHistory
                        )
                    } else currentState
                }
            } else {
                if (currentState.isNewInputExpected) {
                    currentState.copy(
                        display = "0.",
                        isNewInputExpected = false,
                        isFreshSession = false
                    )
                } else {
                    if (!currentState.display.contains(".")) {
                        currentState.copy(
                            display = currentState.display + ".",
                            isFreshSession = false
                        )
                    } else currentState
                }
            }
        }
        saveCurrentState()
    }

    fun onSignChangeClick() {
        _uiState.update { currentState ->
            val num = currentState.display.toDoubleOrNull() ?: 0.0
            val negatedNum = -num
            val formatted = formatDouble(negatedNum)
            
            if (currentState.checkIndex != -1) {
                val newHistory = currentState.historyList.toMutableList()
                if (currentState.checkIndex < newHistory.size && isNumber(newHistory[currentState.checkIndex])) {
                    newHistory[currentState.checkIndex] = formatted
                    currentState.copy(
                        display = formatted,
                        historyList = newHistory,
                        isCorrecting = true
                    )
                } else currentState
            } else {
                currentState.copy(
                    display = formatted,
                    isFreshSession = false
                )
            }
        }
        saveCurrentState()
    }

    fun onOperatorClick(op: String) {
        resetDirectClears()
        _uiState.update { currentState ->
            val curVal = currentState.display.toDoubleOrNull() ?: 0.0
            
            // Exit check mode if operator clicked
            val cleanedHistory = if (currentState.checkIndex != -1) {
                // Keep history up to current display as a fresh sequence
                listOf(currentState.display)
            } else {
                currentState.historyList
            }

            val newHistory = cleanedHistory.toMutableList()
            if (newHistory.isEmpty()) {
                newHistory.add(currentState.display)
            } else if (currentState.isNewInputExpected && isOperator(newHistory.lastOrNull() ?: "")) {
                // Replace the last operator
                newHistory[newHistory.lastIndex] = op
            } else {
                newHistory.add(op)
            }

            var nextPrevious = curVal
            var nextDisplay = currentState.display

            if (currentState.pendingOperator.isNotEmpty() && !currentState.isNewInputExpected) {
                val result = when (currentState.pendingOperator) {
                    "+" -> currentState.previousValue + curVal
                    "-" -> currentState.previousValue - curVal
                    "×" -> currentState.previousValue * curVal
                    "÷" -> if (curVal != 0.0) currentState.previousValue / curVal else 0.0
                    "MU" -> {
                        // Mark Up Cost ÷ Margin MU
                        val prevOp = if (newHistory.size >= 4) newHistory[newHistory.size - 4] else ""
                        if (prevOp == "÷" || prevOp == "/") {
                            if (curVal < 100.0) currentState.previousValue / (1.0 - curVal / 100.0) else currentState.previousValue
                        } else {
                            currentState.previousValue * (1.0 + curVal / 100.0)
                        }
                    }
                    else -> curVal
                }
                nextPrevious = result
                nextDisplay = formatDouble(result)
            }

            currentState.copy(
                display = nextDisplay,
                previousValue = nextPrevious,
                pendingOperator = op,
                isNewInputExpected = true,
                historyList = newHistory,
                checkIndex = -1,
                isCorrecting = false,
                isFreshSession = false
            )
        }
        saveCurrentState()
    }

    fun onEqualClick() {
        resetDirectClears()
        _uiState.update { currentState ->
            val curVal = currentState.display.toDoubleOrNull() ?: 0.0
            
            if (currentState.pendingOperator.isEmpty()) {
                // Standard equal with no operator just sets history to display
                currentState.copy(
                    historyList = listOf(currentState.display),
                    isNewInputExpected = true
                )
            } else {
                val finalOp = currentState.pendingOperator
                val result = when (finalOp) {
                    "+" -> currentState.previousValue + curVal
                    "-" -> currentState.previousValue - curVal
                    "×" -> currentState.previousValue * curVal
                    "÷" -> if (curVal != 0.0) currentState.previousValue / curVal else 0.0
                    "MU" -> {
                        // MU Selling price
                        val hist = currentState.historyList
                        val prevOp = if (hist.size > 2) hist[hist.size - 3] else ""
                        if (prevOp == "÷" || prevOp == "/") {
                            if (curVal < 100.0) currentState.previousValue / (1.0 - curVal / 100.0) else currentState.previousValue
                        } else {
                            currentState.previousValue * (1.0 + curVal / 100.0)
                        }
                    }
                    else -> curVal
                }

                val newHistory = currentState.historyList.toMutableList()
                if (newHistory.lastOrNull() != currentState.display) {
                    newHistory.add(currentState.display)
                }
                newHistory.add("=")
                val resultString = formatDouble(result)
                newHistory.add(resultString)

                // Accumulate to GT
                val newGt = currentState.grandTotal + result

                // Save calculation to archive asynchronously
                val expr = currentState.historyList.joinToString(" ") + " = "
                viewModelScope.launch {
                    repository.insertSavedCalculation(
                        SavedCalculation(
                            expression = expr,
                            result = resultString,
                            isAiSolved = false
                        )
                    )
                }

                currentState.copy(
                    display = resultString,
                    previousValue = result,
                    pendingOperator = "",
                    isNewInputExpected = true,
                    historyList = newHistory,
                    grandTotal = newGt,
                    checkIndex = -1,
                    isCorrecting = false,
                    isFreshSession = false
                )
            }
        }
        saveCurrentState()
    }

    fun onSquareRootClick() {
        _uiState.update { currentState ->
            val curVal = currentState.display.toDoubleOrNull() ?: 0.0
            if (curVal >= 0.0) {
                val sq = sqrt(curVal)
                val sqStr = formatDouble(sq)
                
                val newHistory = currentState.historyList.toMutableList()
                if (currentState.checkIndex != -1) {
                    if (currentState.checkIndex < newHistory.size && isNumber(newHistory[currentState.checkIndex])) {
                        newHistory[currentState.checkIndex] = sqStr
                        currentState.copy(
                            display = sqStr,
                            historyList = newHistory,
                            isCorrecting = true
                        )
                    } else currentState
                } else {
                    // Update current operand
                    currentState.copy(
                        display = sqStr,
                        isFreshSession = false
                    )
                }
            } else {
                currentState.copy(display = "ERROR")
            }
        }
        saveCurrentState()
    }

    fun onMarkupClick() {
        // MU key acts like a specialized equal/operator modifier. Click MU behaves like operation "MU".
        onOperatorClick("MU")
    }

    fun onPercentageClick() {
        resetDirectClears()
        _uiState.update { currentState ->
            val curVal = currentState.display.toDoubleOrNull() ?: 0.0
            
            if (currentState.pendingOperator.isEmpty()) {
                val result = curVal / 100.0
                val resultStr = formatDouble(result)
                currentState.copy(
                    display = resultStr,
                    isNewInputExpected = true,
                    historyList = listOf(resultStr),
                    checkIndex = -1,
                    isCorrecting = false,
                    isFreshSession = false
                )
            } else {
                val op = currentState.pendingOperator
                val percentValue = when (op) {
                    "+", "-" -> currentState.previousValue * (curVal / 100.0)
                    "×", "÷", "*", "/" -> curVal / 100.0
                    else -> curVal / 100.0
                }
                val resultStr = formatDouble(percentValue)
                val newHistory = currentState.historyList.toMutableList()
                if (newHistory.lastOrNull() == currentState.display) {
                    newHistory[newHistory.lastIndex] = resultStr
                } else {
                    newHistory.add(resultStr)
                }
                currentState.copy(
                    display = resultStr,
                    isNewInputExpected = true,
                    historyList = newHistory,
                    checkIndex = -1,
                    isCorrecting = false,
                    isFreshSession = false
                )
            }
        }
        saveCurrentState()
    }

    // --- Memory Operations ---

    fun onMemoryPlusClick() {
        _uiState.update { currentState ->
            val curVal = currentState.display.toDoubleOrNull() ?: 0.0
            currentState.copy(
                memory = currentState.memory + curVal,
                isNewInputExpected = true
            )
        }
        saveCurrentState()
    }

    fun onMemoryMinusClick() {
        _uiState.update { currentState ->
            val curVal = currentState.display.toDoubleOrNull() ?: 0.0
            currentState.copy(
                memory = currentState.memory - curVal,
                isNewInputExpected = true
            )
        }
        saveCurrentState()
    }

    fun onMrcClick() {
        mrcClickCount++
        if (mrcClickCount == 1) {
            // Recall memory value
            _uiState.update { currentState ->
                currentState.copy(
                    display = formatDouble(currentState.memory),
                    isNewInputExpected = true
                )
            }
        } else {
            // Clear memory
            _uiState.update { currentState ->
                currentState.copy(memory = 0.0)
            }
            mrcClickCount = 0
        }
        saveCurrentState()
    }

    fun onGtClick() {
        gtClickCount++
        if (gtClickCount == 1) {
            _uiState.update { currentState ->
                currentState.copy(
                    display = formatDouble(currentState.grandTotal),
                    isNewInputExpected = true
                )
            }
        } else {
            _uiState.update { currentState ->
                currentState.copy(grandTotal = 0.0)
            }
            gtClickCount = 0
        }
        saveCurrentState()
    }

    private fun resetDirectClears() {
        mrcClickCount = 0
        gtClickCount = 0
    }

    // --- Review Methods (CHECK & CORRECT) ---

    fun onCheckPrevClick() {
        _uiState.update { currentState ->
            val size = currentState.historyList.size
            if (size == 0) return@update currentState

            val nextIndex = if (currentState.checkIndex == -1) {
                size - 1 // Start from the end
            } else {
                (currentState.checkIndex - 1).coerceAtLeast(0)
            }

            val stepVal = currentState.historyList.getOrNull(nextIndex) ?: ""
            currentState.copy(
                checkIndex = nextIndex,
                display = stepVal,
                isCorrecting = false
            )
        }
    }

    fun onCheckNextClick() {
        _uiState.update { currentState ->
            val size = currentState.historyList.size
            if (size == 0 || currentState.checkIndex == -1) return@update currentState

            val nextIndex = currentState.checkIndex + 1
            if (nextIndex >= size) {
                // Exit review mode, show the last calculated value or back to "0"
                val finalRes = recalculateFromHistory(currentState.historyList)
                currentState.copy(
                    checkIndex = -1,
                    display = formatDouble(finalRes),
                    isCorrecting = false
                )
            } else {
                val stepVal = currentState.historyList.getOrNull(nextIndex) ?: ""
                currentState.copy(
                    checkIndex = nextIndex,
                    display = stepVal,
                    isCorrecting = false
                )
            }
        }
    }

    fun onCorrectClick() {
        _uiState.update { currentState ->
            if (currentState.checkIndex != -1) {
                val idx = currentState.checkIndex
                val size = currentState.historyList.size
                if (idx < size && isNumber(currentState.historyList[idx])) {
                    if (currentState.isCorrecting) {
                        // Finished correcting! Recalculate everything left-to-right!
                        val recalculateVal = recalculateFromHistory(currentState.historyList)
                        currentState.copy(
                            isCorrecting = false,
                            display = formatDouble(recalculateVal)
                        )
                    } else {
                        // Enter active editing
                        currentState.copy(isCorrecting = true)
                    }
                } else currentState
            } else currentState
        }
        saveCurrentState()
    }

    fun onDeleteClick() {
        _uiState.update { currentState ->
            if (currentState.checkIndex != -1 && currentState.isCorrecting) {
                // Delete character in current edit
                val cur = currentState.display
                val nextStr = if (cur.length <= 1) "0" else cur.substring(0, cur.length - 1)
                val newHistory = currentState.historyList.toMutableList()
                newHistory[currentState.checkIndex] = nextStr
                
                currentState.copy(
                    display = nextStr,
                    historyList = newHistory
                )
            } else if (currentState.checkIndex == -1) {
                // Regular Backspace
                val cur = currentState.display
                val nextStr = if (cur.length <= 1) "0" else cur.substring(0, cur.length - 1)
                currentState.copy(
                    display = nextStr,
                    isFreshSession = false
                )
            } else currentState
        }
        saveCurrentState()
    }

    fun onCeClick() {
        _uiState.update { currentState ->
            currentState.copy(
                display = "0",
                isFreshSession = false
            )
        }
        saveCurrentState()
    }

    fun onOnAcClick() {
        _uiState.update { currentState ->
            currentState.copy(
                display = "0",
                historyList = emptyList(),
                checkIndex = -1,
                isCorrecting = false,
                pendingOperator = "",
                previousValue = 0.0,
                isNewInputExpected = true,
                isFreshSession = true
            )
        }
        saveCurrentState()
    }

    // --- Saved Calculations Archive Operations ---

    fun clearHistoryArchive() {
        viewModelScope.launch {
            repository.clearSavedCalculations()
        }
    }

    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch {
            repository.deleteSavedCalculationById(id)
        }
    }

    // --- Gemini Solving Mode (AI) ---

    fun solveWithGemini(bitmap: Bitmap? = null) {
        val currentPrompt = _uiState.value.aiPromptInput
        if (currentPrompt.isBlank() && bitmap == null) {
            _uiState.update { it.copy(aiErrorMessage = "Prompt is empty. Please describe or draw your calculation problem.") }
            return
        }

        _uiState.update {
            it.copy(
                isAiLoading = true,
                aiErrorMessage = null
            )
        }

        viewModelScope.launch {
            val queryText = if (currentPrompt.isNotBlank()) currentPrompt else "Solve the calculations contained in this image and return the output matching the requested JSON format."
            val result = GeminiManager.solveMath(queryText, bitmap)
            result.fold(
                onSuccess = { calcResult ->
                    _uiState.update { currentState ->
                        // Add to calculations archive
                        viewModelScope.launch {
                            val expr = "AI Solved: " + (if (currentPrompt.isNotBlank()) currentPrompt else "[Canvas Drawing]")
                            repository.insertSavedCalculation(
                                SavedCalculation(
                                    expression = expr,
                                    result = calcResult.display,
                                    isAiSolved = true
                                )
                            )
                        }

                        currentState.copy(
                            display = calcResult.display,
                            historyList = calcResult.history,
                            memory = calcResult.memory,
                            grandTotal = calcResult.grand_total,
                            isAiLoading = false,
                            aiPromptInput = "",
                            activeTab = 0, // Switch back to Standard Mode to display the results!
                            checkIndex = -1,
                            isCorrecting = false,
                            isFreshSession = false
                        )
                    }
                    saveCurrentState()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isAiLoading = false,
                            aiErrorMessage = error.message ?: "An unknown error occurred while calling the Gemini API."
                        )
                    }
                }
            )
        }
    }

    // --- Helper Formatters ---

    private fun formatDouble(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "ERROR"
        // If it's effectively an integer
        if (d % 1.0 == 0.0) {
            val longVal = d.toLong()
            return if (longVal.toString().length > 12) {
                // Fallback to exponential
                String.format("%.6E", d)
            } else {
                longVal.toString()
            }
        }
        
        // Return max 12 character string limit to prevent overflowing LCD displays
        val str = d.toString()
        if (str.length <= 12) return str
        
        // Round to fit 12 characters
        val intDigitsCount = d.toLong().toString().length
        val decDigitsToKeep = (10 - intDigitsCount).coerceAtLeast(0)
        return try {
            val rounded = String.format("%.${decDigitsToKeep}f", d)
            // Trim trailing zeros
            var cleaned = rounded.trimEnd('0')
            if (cleaned.endsWith(".")) {
                cleaned = cleaned.substring(0, cleaned.length - 1)
            }
            if (cleaned.length > 12) cleaned.substring(0, 12) else cleaned
        } catch (e: Exception) {
            str.substring(0, 12)
        }
    }

    private fun isNumber(s: String): Boolean {
        return s.toDoubleOrNull() != null
    }

    private fun isOperator(s: String): Boolean {
        return s in listOf("+", "-", "×", "÷", "MU", "*", "/")
    }

    private fun recalculateFromHistory(history: List<String>): Double {
        if (history.isEmpty()) return 0.0
        var currentResult = history.firstOrNull()?.toDoubleOrNull() ?: 0.0
        var i = 1
        while (i < history.size) {
            val op = history.getOrNull(i) ?: break
            val nextValStr = history.getOrNull(i + 1) ?: "0"
            val nextVal = nextValStr.toDoubleOrNull() ?: 0.0
            
            if (op == "=") {
                // Done
                break
            }

            currentResult = when (op) {
                "+" -> currentResult + nextVal
                "-" -> currentResult - nextVal
                "×", "*" -> currentResult * nextVal
                "÷", "/" -> if (nextVal != 0.0) currentResult / nextVal else 0.0
                "MU" -> {
                    val prevOp = if (i >= 2) history.getOrNull(i - 2) else null
                    if (prevOp == "÷" || prevOp == "/") {
                        if (nextVal < 100.0) currentResult / (1.0 - nextVal / 100.0) else currentResult
                    } else {
                        currentResult * (1.0 + nextVal / 100.0)
                    }
                }
                else -> currentResult
            }
            i += 2
        }
        return currentResult
    }
}

// --- Composable State Wrapper ---

data class CalculatorUiState(
    val display: String = "0",
    val historyList: List<String> = emptyList(),
    val memory: Double = 0.0,
    val grandTotal: Double = 0.0,
    
    // Check & Correct variables
    val checkIndex: Int = -1,
    val isCorrecting: Boolean = false,
    
    // Operational states
    val pendingOperator: String = "",
    val previousValue: Double = 0.0,
    val isNewInputExpected: Boolean = true,
    
    // AI Variables
    val activeTab: Int = 0, // 0 = Standard, 1 = AI Canvas
    val aiPromptInput: String = "",
    val isAiLoading: Boolean = false,
    val aiErrorMessage: String? = null,
    
    // Saved database history
    val savedCalculations: List<SavedCalculation> = emptyList(),
    val isFreshSession: Boolean = true
)
