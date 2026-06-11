package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.SavedCalculation
import com.example.network.GeminiManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.InputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RozCalculateApp(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AppBackground)
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

// Data class to persist drawing lines
data class Line(
    val points: List<Offset> = emptyList(),
    val color: Color = Color.White,
    val strokeWidth: Float = 10f
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RozCalculateApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: com.example.viewmodel.CalculatorViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    
    // Drawing states
    val lines = remember { mutableStateListOf<Line>() }
    var activeLine by remember { mutableStateOf<Line?>(null) }
    
    // In-memory offscreen bitmap for Gemini drawing submission (White ink on Black bg)
    val offscreenBitmap = remember {
        Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
    }
    val offscreenCanvas = remember {
        AndroidCanvas(offscreenBitmap).apply {
            drawColor(AndroidColor.BLACK)
        }
    }
    val offscreenPaint = remember {
        AndroidPaint().apply {
            color = AndroidColor.WHITE
            style = AndroidPaint.Style.STROKE
            strokeWidth = 14f
            strokeCap = AndroidPaint.Cap.ROUND
            isAntiAlias = true
        }
    }
    
    // Image solver variables
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var pickedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Help details dialog
    var showHelpDialog by remember { mutableStateOf(false) }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pickedImageUri = uri
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val original = BitmapFactory.decodeStream(inputStream)
                    // Scale down for Gemini optimal payload
                    var width = original.width
                    var height = original.height
                    val maxDimension = 1024
                    if (width > maxDimension || height > maxDimension) {
                        if (width > height) {
                            height = (height * (maxDimension.toFloat() / width)).toInt()
                            width = maxDimension
                        } else {
                            width = (width * (maxDimension.toFloat() / height)).toInt()
                            height = maxDimension
                        }
                    }
                    pickedImageBitmap = Bitmap.createScaledBitmap(original, width, height, true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(modifier = modifier) {
        // TOP LOGO & APP BAR with TAB SWITCH
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = SlateChassis),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RozCalculate",
                            color = YellowAccent,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.SansSerif,
                            modifier = Modifier.testTag("app_logo_title")
                        )
                        Text(
                            text = "Roz 11",
                            color = Color.LightGray.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { showHelpDialog = true },
                            modifier = Modifier.background(ChassisLight, CircleShape)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "How to Use", tint = YellowAccent)
                        }
                        if (!GeminiManager.isApiKeyConfigured()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = AmberAccent.copy(alpha = 0.2f)),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, AmberAccent),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = "API Warning", tint = AmberAccent, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("No API Key", color = AmberAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Segmented tab bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ChassisLight)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabModifier = Modifier.weight(1f)
                    
                    TabButton(
                        text = "📟 Roz-11 Desktop",
                        active = uiState.activeTab == 0,
                        onClick = { viewModel.changeTab(0) },
                        modifier = tabModifier
                    )
                    
                    TabButton(
                        text = "✨ AI VISION MATH",
                        active = uiState.activeTab == 1,
                        onClick = { viewModel.changeTab(1) },
                        modifier = tabModifier
                    )
                }
            }
        }

        // CONTENT INNER SWITCH
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (uiState.activeTab == 0) {
                // DESKTOP PHYSICAL REPLICA
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // RETRO GREEN LCD PANEL
                    RetroLcdDisplay(uiState = uiState)
                    
                    // KEYBOARD CHASSIS
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SlateChassis)
                            .border(1.5.dp, ChassisLight, RoundedCornerShape(14.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // ROW 1: CHECK & AUDIT KEYS (◀, ▶, CORRECT, CE, DELETE)
                        Row(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CalcButton(
                                text = "◀ CHECK",
                                color = YellowAccent,
                                textColor = Color.Black,
                                onClick = { viewModel.onCheckPrevClick() },
                                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                                testTag = "check_prev_button"
                            )
                            CalcButton(
                                text = "CHECK ▶",
                                color = YellowAccent,
                                textColor = Color.Black,
                                onClick = { viewModel.onCheckNextClick() },
                                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                                testTag = "check_next_button"
                            )
                            CalcButton(
                                text = "CORRECT",
                                color = AmberAccent,
                                textColor = Color.White,
                                onClick = { viewModel.onCorrectClick() },
                                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                                testTag = "correct_button"
                            )
                            CalcButton(
                                text = "CE",
                                color = Color(0xFF6C7A89),
                                textColor = Color.White,
                                onClick = { viewModel.onCeClick() },
                                modifier = Modifier.weight(0.8f).fillMaxHeight(),
                                testTag = "ce_button"
                            )
                            CalcButton(
                                text = "◀x",
                                color = Color(0xFF6C7A89),
                                textColor = Color.White,
                                onClick = { viewModel.onDeleteClick() },
                                modifier = Modifier.weight(0.8f).fillMaxHeight(),
                                testTag = "backspace_button"
                            )
                        }

                        // ROW 2: FUNCTION & MEMORY CONTROLS (ON/AC, GT, MU, +/- , √)
                        Row(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CalcButton(
                                text = "ON/AC",
                                color = AmberAccent,
                                textColor = Color.White,
                                onClick = { viewModel.onOnAcClick() },
                                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                                testTag = "on_ac_button"
                            )
                            CalcButton(
                                text = "GT",
                                color = TealMemory,
                                textColor = Color.White,
                                onClick = { viewModel.onGtClick() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                testTag = "gt_button"
                            )
                            CalcButton(
                                text = "MU",
                                color = TealMemory,
                                textColor = Color.White,
                                onClick = { viewModel.onMarkupClick() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                testTag = "markup_button"
                            )
                            CalcButton(
                                text = "+/-",
                                color = TealMemory,
                                textColor = Color.White,
                                onClick = { viewModel.onSignChangeClick() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                testTag = "sign_change_button"
                            )
                            CalcButton(
                                text = "√",
                                color = TealMemory,
                                textColor = Color.White,
                                onClick = { viewModel.onSquareRootClick() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                testTag = "sqrt_button"
                            )
                        }

                        // ROW 3: MEMORY SERIES (MRC, M-, M+), % & DIVIDE (÷)
                        Row(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CalcButton(
                                text = "MRC",
                                color = TealMemory,
                                textColor = Color.White,
                                onClick = { viewModel.onMrcClick() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                testTag = "mrc_button"
                            )
                            CalcButton(
                                text = "M-",
                                color = TealMemory,
                                textColor = Color.White,
                                onClick = { viewModel.onMemoryMinusClick() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                testTag = "m_minus_button"
                            )
                            CalcButton(
                                text = "M+",
                                color = TealMemory,
                                textColor = Color.White,
                                onClick = { viewModel.onMemoryPlusClick() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                testTag = "m_plus_button"
                            )
                            CalcButton(
                                text = "%",
                                color = OrangeOperator,
                                textColor = Color.White,
                                onClick = { viewModel.onPercentageClick() },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                testTag = "percent_button"
                            )
                            CalcButton(
                                text = "÷",
                                color = OrangeOperator,
                                textColor = Color.White,
                                onClick = { viewModel.onOperatorClick("÷") },
                                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                                testTag = "div_button"
                            )
                        }

                        // ROW 4-7: CORE GRID LAYOUT (Numeric + Multiply / Subtract / Add / Equal)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Left Numpad 3x4 block
                            Column(
                                modifier = Modifier.weight(3.6f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CalcButton(text = "7", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("7") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num7_button")
                                    CalcButton(text = "8", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("8") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num8_button")
                                    CalcButton(text = "9", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("9") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num9_button")
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CalcButton(text = "4", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("4") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num4_button")
                                    CalcButton(text = "5", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("5") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num5_button")
                                    CalcButton(text = "6", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("6") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num6_button")
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CalcButton(text = "1", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("1") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num1_button")
                                    CalcButton(text = "2", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("2") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num2_button")
                                    CalcButton(text = "3", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("3") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num3_button")
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CalcButton(text = "0", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("0") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num0_button")
                                    CalcButton(text = "00", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDigitClick("00") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "num00_button")
                                    CalcButton(text = ".", color = KeyNumber, textColor = KeyNumberText, onClick = { viewModel.onDecimalClick() }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "dot_button")
                                }
                            }
                            
                            // Right Arithmetic column
                            Column(
                                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CalcButton(text = "×", color = OrangeOperator, textColor = Color.White, onClick = { viewModel.onOperatorClick("×") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "mul_button")
                                CalcButton(text = "-", color = OrangeOperator, textColor = Color.White, onClick = { viewModel.onOperatorClick("-") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "sub_button")
                                CalcButton(text = "+", color = OrangeOperator, textColor = Color.White, onClick = { viewModel.onOperatorClick("+") }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "add_button")
                                CalcButton(text = "=", color = YellowAccent, textColor = Color.Black, onClick = { viewModel.onEqualClick() }, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = "equal_button")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            } else {
                // AI VISION SOLVER & HISTORY ARCHIVE
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "Drawing Canvas / Handwritten Equation Solver",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        // DRAWING BOARD BOX
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                                .border(1.5.dp, ChassisLight, RoundedCornerShape(12.dp))
                        ) {
                            if (pickedImageBitmap != null) {
                                // Show Uploaded math image instead of drawing
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = pickedImageUri,
                                        contentDescription = "Uploaded math equation",
                                        modifier = Modifier.fillMaxSize().padding(12.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            pickedImageBitmap = null
                                            pickedImageUri = null
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                            .background(Color.Red.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear Image", tint = Color.White)
                                    }
                                }
                            } else {
                                // Draw canvas active
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragStart = { offset ->
                                                    activeLine = Line(points = listOf(offset))
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    activeLine?.let { line ->
                                                        val nextPoints = line.points + change.position
                                                        activeLine = line.copy(points = nextPoints)
                                                        
                                                        // Mirror immediately to offscreen bitmap canvas
                                                        if (nextPoints.size >= 2) {
                                                            val p1 = nextPoints[nextPoints.size - 2]
                                                            val p2 = nextPoints.last()
                                                            offscreenCanvas.drawLine(p1.x, p1.y, p2.x, p2.y, offscreenPaint)
                                                        }
                                                    }
                                                },
                                                onDragEnd = {
                                                    activeLine?.let { line ->
                                                        lines.add(line)
                                                    }
                                                    activeLine = null
                                                }
                                            )
                                        }
                                ) {
                                    // Render all drawn lines
                                    lines.forEach { line ->
                                        if (line.points.size >= 2) {
                                            for (i in 0 until line.points.size - 1) {
                                                drawLine(
                                                    color = Color.White,
                                                    start = line.points[i],
                                                    end = line.points[i + 1],
                                                    strokeWidth = 6.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                            }
                                        }
                                    }
                                    // Active line render
                                    activeLine?.let { line ->
                                        if (line.points.size >= 2) {
                                            for (i in 0 until line.points.size - 1) {
                                                drawLine(
                                                    color = Color.White,
                                                    start = line.points[i],
                                                    end = line.points[i + 1],
                                                    strokeWidth = 6.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                if (lines.isEmpty() && activeLine == null) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Draw here", tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(44.dp))
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "Draw a math problem with your finger\n(e.g., √256 + 12 - 3)\nOr upload a math photo",
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // CANVAS QUICK CONTROLS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    lines.clear()
                                    activeLine = null
                                    offscreenCanvas.drawColor(AndroidColor.BLACK)
                                    pickedImageBitmap = null
                                    pickedImageUri = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ChassisLight),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear Canvas")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear", fontSize = 12.sp)
                            }
                            
                            Button(
                                onClick = { imageLauncher.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = ChassisLight),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1.2f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Upload Math")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Upload Photo", fontSize = 12.sp)
                            }
                        }
                    }
                    
                    item {
                        // COMPLEX NATURAL LANGUAGE TEXT BAR
                        Text(
                            text = "Or type a calculation query (with cost margins, tax or steps)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        TextField(
                            value = uiState.aiPromptInput,
                            onValueChange = { viewModel.updateAiPromptInput(it) },
                            placeholder = { Text("What is the Selling Price if Cost is $150 and Margin is 25% using MU?") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_prompt_text_field"),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = SlateChassis,
                                unfocusedContainerColor = SlateChassis,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = YellowAccent
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        // SOLVE BUTTON WITH SPARKLING GEMINI POWER
                        Button(
                            onClick = {
                                val submitBitmap = if (pickedImageBitmap != null) {
                                    pickedImageBitmap
                                } else if (lines.isNotEmpty()) {
                                    offscreenBitmap
                                } else null
                                viewModel.solveWithGemini(submitBitmap)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("solve_with_gemini_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (uiState.isAiLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Gemini Solving...", color = Color.White)
                            } else {
                                Icon(Icons.Default.Star, contentDescription = "Gemini Spark", tint = Color.White)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("SOLVE WITH GEMINI AI", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        // Error message feedback
                        uiState.aiErrorMessage?.let { err ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F).copy(alpha = 0.2f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD32F2F)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = Color.Red)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(err, color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // SAVED HISTORY HEADER
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📜 Calculation Session Archive",
                                color = Color.LightGray,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (uiState.savedCalculations.isNotEmpty()) {
                                Text(
                                    text = "Clear All",
                                    color = OrangeOperator,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { viewModel.clearHistoryArchive() }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    
                    if (uiState.savedCalculations.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                colors = CardDefaults.cardColors(containerColor = SlateChassis.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "Tape calculation reel is dry. Tap '=' or use Gemini AI to solve equations and write to tape.",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(14.dp),
                                    textAlign = TextAlign.Center,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    } else {
                        items(uiState.savedCalculations) { calc ->
                            ArchiveDisplayRow(
                                calc = calc,
                                onDelete = { viewModel.deleteHistoryItem(calc.id) },
                                onSelect = {
                                    // Load directly back to LCD screen
                                    viewModel.onDigitClick(calc.result)
                                }
                            )
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
    
    // INFO HELP DIALOGUE
    if (showHelpDialog) {
        Dialog(onDismissRequest = { showHelpDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateChassis),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChassisLight),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "RozCalculate Guide",
                        color = YellowAccent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Divider(color = ChassisLight)
                    
                    val scrollableHelpTextModifier = Modifier
                        .maxHeight(260.dp)
                        .verticalScroll()
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        Text(
                            text = "📟 Roz-11 Replica Mode:\n" +
                                   "• Standard Operations (+, -, ×, ÷, %, √) calculate immediately left-to-right.\n" +
                                   "• Check & Correct Series: Tap checking arrows ◀ / ▶ to view steps. While viewing a numeric step, you can type characters and click CORRECT to change values and recalculate sequence.\n" +
                                   "• Mark Up (MU): Tap Cost ÷ Margin% MU to compute retail pricing.\n" +
                                   "• Grand Total (GT): Results of every '=' accumulate to GT. Double click clear.\n" +
                                   "• Memory Panel: Add (M+) or subtract (M-). Tap MRC once to view, twice clears.\n\n" +
                                   "✨ Gemini AI Mode:\n" +
                                   "• Draw complete formulas with your finger on the slate blackboard or upload a mobile photo!\n" +
                                   "• Describe your business markup query or tax question in plain text.\n" +
                                   "• Solved responses configure results, memory variables and step logs into the Roz-11 LCD seamlessly!",
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            lineHeight = 17.sp
                        )
                    }
                    
                    Divider(color = ChassisLight)
                    
                    Button(
                        onClick = { showHelpDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Get Started")
                    }
                }
            }
        }
    }
}

@Composable
fun TabButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) SlateChassis else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) YellowAccent else Color.LightGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RetroLcdDisplay(
    uiState: com.example.viewmodel.CalculatorUiState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .border(2.dp, ChassisLight, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroGreenLCD),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // INDICATOR FLAG ROW (M, GT, CORRECT, CHECK/STEP)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Indicators
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (uiState.memory != 0.0) {
                        MTextFlag("M")
                    }
                    if (uiState.grandTotal != 0.0) {
                        MTextFlag("GT")
                    }
                    if (uiState.isCorrecting) {
                        MTextFlag("CORRECT")
                    }
                }
                
                // Right Indicators (Check review steps tracker)
                if (uiState.checkIndex != -1) {
                    val stepText = "CHECK STEP ${uiState.checkIndex + 1}/${uiState.historyList.size}"
                    Text(
                        text = stepText,
                        color = RetroGreenLCDText,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.testTag("lcd_check_step_lbl")
                    )
                } else if (uiState.pendingOperator.isNotEmpty()) {
                    MTextFlag("OP: ${uiState.pendingOperator}")
                }
            }

            // DOT-MATRIX ACTION STEPS SEQUENCE TAPE
            Text(
                text = if (uiState.isFreshSession && uiState.historyList.isEmpty()) "ROZCALCULATE ACTIVE" else uiState.historyList.joinToString(" "),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = RetroGreenLCDText.copy(alpha = 0.65f),
                maxLines = 1,
                textAlign = TextAlign.Left,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lcd_formula_tape_text")
            )

            // MAIN SEVENTEEN SEGMENT DISPLAY CHARACTERS
            Text(
                text = uiState.display,
                color = RetroGreenLCDText,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Right,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lcd_main_numeric_text"),
                maxLines = 1
            )
        }
    }
}

@Composable
fun MTextFlag(text: String) {
    Box(
        modifier = Modifier
            .background(RetroGreenLCDText, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = RetroGreenLCD,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun CalcButton(
    text: String,
    color: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.45f)) // Simulated shadows background
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.93f)
                .background(color, RoundedCornerShape(8.dp))
                .align(Alignment.TopCenter),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ArchiveDisplayRow(
    calc: SavedCalculation,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = SlateChassis),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (calc.isAiSolved) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "AI solved",
                            tint = YellowAccent,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = calc.expression,
                        color = Color.LightGray.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "= " + calc.result,
                    color = YellowAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete archive log",
                    tint = Color.Gray.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// Custom Border Helper to avoid adding too many styles
private fun borderStroke(width: androidx.compose.ui.unit.Dp, color: Color) = 
    Modifier.border(width, color, RoundedCornerShape(8.dp))

// Extension modifier helpers to maintain clean sizing
private fun Modifier.maxHeight(dp: androidx.compose.ui.unit.Dp) = this.heightIn(max = dp)
private fun Modifier.verticalScroll(): Modifier {
    return this.heightIn(max = 240.dp) // Simulated fallback
}

