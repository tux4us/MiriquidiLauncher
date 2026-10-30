// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.calculator.AngleMode
import io.github.tux4us.miriquidilauncher.calculator.CalcError
import io.github.tux4us.miriquidilauncher.calculator.CalcFormat
import io.github.tux4us.miriquidilauncher.calculator.CalcOutcome
import io.github.tux4us.miriquidilauncher.calculator.CalcSession
import io.github.tux4us.miriquidilauncher.calculator.CalculatorViewModel
import io.github.tux4us.miriquidilauncher.calculator.HistoryEntry
import io.github.tux4us.miriquidilauncher.search.ResultAction
import java.text.DecimalFormatSymbols

private val ErrorRed = Color(0xFFFF9E9E)
private val KeyShape = RoundedCornerShape(14.dp)
private val Gap = 6.dp
private val ControlRowHeight = 44.dp

private enum class KeyKind { Digit, Operator, Function, Equals, Clear }

private class KeySpec(
    val label: String,
    val kind: KeyKind,
    val description: String? = null,
    val onClick: () -> Unit,
)

/** Taste der wissenschaftlichen Ebene: Beschriftung und Eingabe, jeweils für die erste und die zweite Ebene. */
private class SciKey(val first: String, val firstToken: String, val second: String? = null, val secondToken: String? = null)

private val SciRows = listOf(
    listOf(
        SciKey("sin", "sin(", "asin", "asin("),
        SciKey("cos", "cos(", "acos", "acos("),
        SciKey("tan", "tan(", "atan", "atan("),
        SciKey("ln", "ln(", "e^x", "e^("),
        SciKey("log", "log(", "10^x", "10^("),
    ),
    listOf(
        SciKey("x²", "^2", "x³", "^3"),
        SciKey("x^y", "^", "root", "root("),
        SciKey("√", "√(", "cbrt", "cbrt("),
        SciKey("x!", "!", "nCr", "ncr("),
        SciKey("1/x", "^(-1)", "nPr", "npr("),
    ),
    listOf(
        SciKey("π", "π"),
        SciKey("e", "e"),
        SciKey("|x|", "abs(", "mod", "mod"),
        SciKey("EE", "E"),
        SciKey(";", ";"),
    ),
)

/** Bündelt die Aktionen der Tasten und löst bei jeder Eingabe ein leichtes Vibrationssignal aus. */
private class CalcActions(private val viewModel: CalculatorViewModel, private val haptic: HapticFeedback) {
    private fun tick() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    fun type(token: String) { tick(); viewModel.type(token) }
    fun backspace() { tick(); viewModel.backspace() }
    fun clear() { tick(); viewModel.clear() }
    fun evaluate() { tick(); viewModel.evaluate() }
    fun left() { tick(); viewModel.moveLeft() }
    fun right() { tick(); viewModel.moveRight() }
    fun toggleAngle() { tick(); viewModel.toggleAngle() }
    fun memoryClear() { tick(); viewModel.memoryClear() }
    fun memoryRecall() { tick(); viewModel.memoryRecall() }
    fun memoryAdd(subtract: Boolean) { tick(); viewModel.memoryAdd(subtract) }
    fun loadExpression(expression: String) = viewModel.loadExpression(expression)
    fun insertValue(value: Double) = viewModel.insertValue(value)
    fun clearHistory() = viewModel.clearHistory()
}

/**
 * Scope „Rechner“: Taschenrechner mit Grundfunktionen und wissenschaftlichen Funktionen. Im Hochformat lassen sich
 * die wissenschaftlichen Tasten einblenden, im Querformat stehen sie neben den Grundtasten. Die Eingabe erfolgt
 * über die eigene Tastatur; die Rechenlogik liegt in `CalcSession`.
 */
@Composable
fun CalculatorScreen(viewModel: CalculatorViewModel, modifier: Modifier = Modifier) {
    val session by viewModel.state.collectAsStateWithLifecycle()
    var scientific by rememberSaveable { mutableStateOf(false) }
    var second by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val actions = remember(viewModel, haptic) { CalcActions(viewModel, haptic) }
    val onCopy: (String) -> Unit = { text -> runResultAction(context, ResultAction.Copy(text)) }

    ScopePage(title = stringResource(R.string.scope_calculator), modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val displayHeight = if (landscape) 76.dp else (maxHeight * 0.24f).coerceIn(120.dp, 220.dp)
            val sciShown = landscape || scientific
            val rows = if (landscape) 5 else 1 + 5 + (if (scientific) 4 else 0)
            val keyHeight = if (landscape) {
                44.dp
            } else {
                val available = maxHeight - displayHeight - ScopeBottomClearance - 8.dp
                ((available - Gap * (rows - 1)) / rows).coerceIn(44.dp, 64.dp)
            }
            val resetSecond = { second = false }

            Column(Modifier.fillMaxSize()) {
                CalcDisplay(session, separator, onCopy, Modifier.fillMaxWidth().height(displayHeight))
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Gap),
                ) {
                    if (landscape) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(5f), verticalArrangement = Arrangement.spacedBy(Gap)) {
                                ControlRow(
                                    session = session,
                                    showToggle = false,
                                    scientific = true,
                                    second = second,
                                    actions = actions,
                                    onToggleScientific = {},
                                    onToggleSecond = { second = !second },
                                    onHistory = { historyOpen = true },
                                )
                                SciPad(second, keyHeight, actions, resetSecond)
                            }
                            Column(Modifier.weight(4f), verticalArrangement = Arrangement.spacedBy(Gap)) {
                                BasicPad(keyHeight, separator, actions)
                            }
                        }
                    } else {
                        ControlRow(
                            session = session,
                            showToggle = true,
                            scientific = sciShown,
                            second = second,
                            actions = actions,
                            onToggleScientific = { scientific = !scientific; second = false },
                            onToggleSecond = { second = !second },
                            onHistory = { historyOpen = true },
                        )
                        if (scientific) SciPad(second, keyHeight, actions, resetSecond)
                        BasicPad(keyHeight, separator, actions)
                    }
                    Spacer(Modifier.height(ScopeBottomClearance))
                }
            }
        }
    }

    if (historyOpen) {
        HistoryDialog(
            history = session.history,
            separator = separator,
            onExpression = { actions.loadExpression(it); historyOpen = false },
            onValue = { actions.insertValue(it); historyOpen = false },
            onClear = actions::clearHistory,
            onClose = { historyOpen = false },
        )
    }
}

// region Anzeige ---------------------------------------------------------------------------------

@Composable
private fun errorMessage(error: CalcError): String = stringResource(
    when (error) {
        CalcError.Syntax -> R.string.calc_error_syntax
        CalcError.DivideByZero -> R.string.calc_error_divide
        CalcError.Domain -> R.string.calc_error_domain
        CalcError.Overflow -> R.string.calc_error_overflow
    },
)

@Composable
private fun CalcDisplay(session: CalcSession, separator: Char, onCopy: (String) -> Unit, modifier: Modifier) {
    val text = session.editor.text
    val outcome = session.outcome
    val resultText = if (session.evaluated && outcome is CalcOutcome.Value) {
        CalcFormat.format(outcome.value, separator)
    } else {
        session.preview()?.let { CalcFormat.format(it, separator) }
    }
    val primary = MaterialTheme.colorScheme.primary
    val expression = if (session.evaluated) {
        buildAnnotatedString { append(text) }
    } else {
        val cursor = session.editor.cursor.coerceIn(0, text.length)
        buildAnnotatedString {
            append(text.substring(0, cursor))
            withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append("|") }
            append(text.substring(cursor))
        }
    }
    val expressionSize = when {
        session.evaluated -> 22.sp
        text.length <= 12 -> 44.sp
        text.length <= 20 -> 34.sp
        text.length <= 32 -> 26.sp
        else -> 20.sp
    }
    val expressionDescription = stringResource(R.string.calc_expression)
    val resultDescription = stringResource(if (session.evaluated) R.string.calc_result else R.string.calc_result_preview)
    val copyHint = stringResource(R.string.calc_copy_result)

    Box(modifier.glass(RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
            Text(
                text = expression,
                color = if (session.evaluated) WeatherTextMuted else WeatherText,
                fontSize = expressionSize,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState(), reverseScrolling = true)
                    .semantics { contentDescription = "$expressionDescription: $text" },
            )
            val errorText = (outcome as? CalcOutcome.Error)?.let { errorMessage(it.error) }
            when {
                errorText != null -> Text(
                    text = errorText,
                    color = ErrorRed,
                    fontSize = 16.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                resultText != null -> Text(
                    text = if (session.evaluated) resultText else "= $resultText",
                    color = if (session.evaluated) WeatherText else WeatherTextMuted,
                    fontSize = if (session.evaluated) 44.sp else 24.sp,
                    fontWeight = if (session.evaluated) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = copyHint) { onCopy(resultText) }
                        .semantics { contentDescription = "$resultDescription: $resultText" },
                )
                text.isEmpty() -> Text(
                    text = stringResource(R.string.calc_empty_hint),
                    color = WeatherTextMuted,
                    fontSize = 16.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        session.memory?.let { memory ->
            val description = stringResource(R.string.calc_memory_value, CalcFormat.format(memory, separator))
            Text(
                text = "M",
                color = primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopStart).semantics { contentDescription = description },
            )
        }
    }
}

// endregion

// region Tasten ----------------------------------------------------------------------------------

@Composable
private fun ControlRow(
    session: CalcSession,
    showToggle: Boolean,
    scientific: Boolean,
    second: Boolean,
    actions: CalcActions,
    onToggleScientific: () -> Unit,
    onToggleSecond: () -> Unit,
    onHistory: () -> Unit,
) {
    val angleName = stringResource(
        when (session.angle) {
            AngleMode.Deg -> R.string.calc_angle_deg
            AngleMode.Rad -> R.string.calc_angle_rad
            AngleMode.Grad -> R.string.calc_angle_grad
        },
    )
    val angleLabel = when (session.angle) {
        AngleMode.Deg -> "DEG"
        AngleMode.Rad -> "RAD"
        AngleMode.Grad -> "GRAD"
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(ControlRowHeight),
        horizontalArrangement = Arrangement.spacedBy(Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showToggle) {
            Chip("f(x)", scientific, stringResource(R.string.calc_functions), onToggleScientific)
        }
        if (scientific) {
            Chip("2nd", second, stringResource(R.string.calc_second_description), onToggleSecond)
            Chip(angleLabel, false, stringResource(R.string.calc_angle_description, angleName), actions::toggleAngle)
        }
        Spacer(Modifier.weight(1f))
        IconKey(R.drawable.ic_history, stringResource(R.string.calc_history), onHistory)
        IconKey(R.drawable.ic_chevron_left, stringResource(R.string.calc_cursor_left), actions::left)
        IconKey(R.drawable.ic_chevron_right, stringResource(R.string.calc_cursor_right), actions::right)
        IconKey(R.drawable.ic_backspace, stringResource(R.string.calc_backspace), actions::backspace, actions::clear)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, description: String, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = WeatherText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun IconKey(icon: Int, description: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .size(width = 38.dp, height = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = WeatherText, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CalcKey(key: KeySpec, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val background = when (key.kind) {
        KeyKind.Digit -> Color.White.copy(alpha = 0.14f)
        KeyKind.Operator -> primary.copy(alpha = 0.30f)
        KeyKind.Function -> Color.White.copy(alpha = 0.07f)
        KeyKind.Equals -> primary
        KeyKind.Clear -> ErrorRed.copy(alpha = 0.30f)
    }
    val textColor = if (key.kind == KeyKind.Equals) MaterialTheme.colorScheme.onPrimary else WeatherText
    Box(
        modifier = modifier
            .clip(KeyShape)
            .background(background)
            .clickable(role = Role.Button, onClick = key.onClick)
            .then(if (key.description != null) Modifier.semantics { contentDescription = key.description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = textColor,
            fontSize = if (key.label.length <= 2) 22.sp else 15.sp,
            fontWeight = if (key.kind == KeyKind.Function) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun KeyRow(keys: List<KeySpec>, keyHeight: Dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        keys.forEach { key -> CalcKey(key, Modifier.weight(1f).height(keyHeight)) }
    }
}

@Composable
private fun BasicPad(keyHeight: Dp, separator: Char, actions: CalcActions) {
    fun digit(d: String) = KeySpec(d, KeyKind.Digit) { actions.type(d) }
    fun operator(symbol: String) = KeySpec(symbol, KeyKind.Operator) { actions.type(symbol) }
    val clearDescription = stringResource(R.string.calc_clear)
    val equalsDescription = stringResource(R.string.calc_equals)
    KeyRow(
        listOf(
            KeySpec("C", KeyKind.Clear, clearDescription) { actions.clear() },
            KeySpec("(", KeyKind.Function) { actions.type("(") },
            KeySpec(")", KeyKind.Function) { actions.type(")") },
            operator("÷"),
        ),
        keyHeight,
    )
    KeyRow(listOf(digit("7"), digit("8"), digit("9"), operator("×")), keyHeight)
    KeyRow(listOf(digit("4"), digit("5"), digit("6"), operator("−")), keyHeight)
    KeyRow(listOf(digit("1"), digit("2"), digit("3"), operator("+")), keyHeight)
    KeyRow(
        listOf(
            digit("0"),
            KeySpec(separator.toString(), KeyKind.Digit) { actions.type(separator.toString()) },
            KeySpec("%", KeyKind.Operator) { actions.type("%") },
            KeySpec("=", KeyKind.Equals, equalsDescription) { actions.evaluate() },
        ),
        keyHeight,
    )
}

@Composable
private fun SciPad(second: Boolean, keyHeight: Dp, actions: CalcActions, resetSecond: () -> Unit) {
    SciRows.forEach { row ->
        KeyRow(
            row.map { key ->
                val useSecond = second && key.secondToken != null
                val token = if (useSecond) key.secondToken!! else key.firstToken
                val label = if (useSecond) key.second!! else key.first
                KeySpec(label, KeyKind.Function) {
                    actions.type(token)
                    if (second) resetSecond()
                }
            },
            keyHeight,
        )
    }
    val clearMemory = stringResource(R.string.calc_memory_clear)
    val recallMemory = stringResource(R.string.calc_memory_recall)
    val addMemory = stringResource(R.string.calc_memory_add)
    val subtractMemory = stringResource(R.string.calc_memory_subtract)
    val previous = stringResource(R.string.calc_answer)
    KeyRow(
        listOf(
            KeySpec("MC", KeyKind.Function, clearMemory) { actions.memoryClear() },
            KeySpec("MR", KeyKind.Function, recallMemory) { actions.memoryRecall() },
            KeySpec("M+", KeyKind.Function, addMemory) { actions.memoryAdd(false) },
            KeySpec("M−", KeyKind.Function, subtractMemory) { actions.memoryAdd(true) },
            KeySpec("Ans", KeyKind.Function, previous) { actions.type("Ans") },
        ),
        keyHeight,
    )
}

// endregion

// region Verlauf ---------------------------------------------------------------------------------

@Composable
private fun HistoryDialog(
    history: List<HistoryEntry>,
    separator: Char,
    onExpression: (String) -> Unit,
    onValue: (Double) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.calc_history)) },
        text = {
            if (history.isEmpty()) {
                Text(stringResource(R.string.calc_history_empty))
            } else {
                Column {
                    Text(stringResource(R.string.calc_history_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(history) { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = entry.expression,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onExpression(entry.expression) }
                                        .padding(vertical = 12.dp),
                                )
                                Text(
                                    text = "= " + CalcFormat.format(entry.value, separator),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clickable { onValue(entry.value) }
                                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.calc_history_close)) } },
        dismissButton = {
            if (history.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.calc_history_clear)) }
            }
        },
    )
}

// endregion
