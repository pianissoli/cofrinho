package cofrinho.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cofrinho.finance.IdentifierSource
import cofrinho.finance.IncomeSource
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerClock
import cofrinho.finance.LedgerService
import cofrinho.finance.MoneyRules
import cofrinho.finance.PaymentMethod
import cofrinho.finance.RecurringExpense
import cofrinho.finance.TransferState
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private typealias ActionRunner = (() -> Unit) -> Unit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                runCatching { AndroidLedgerStore(this@MainActivity) }
                    .fold({ CofrinhoApp(it) }, { IncompatibleDatabaseScreen(it.message.orEmpty()) })
            }
        }
    }
}

@Composable
private fun IncompatibleDatabaseScreen(message: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Incompatible ledger", style = MaterialTheme.typography.headlineMedium)
        Text(message)
        Text("This release requires a new ledger. Reset app storage or create a fresh ledger before importing new-format data.")
    }
}

@Composable
private fun CofrinhoApp(store: AndroidLedgerStore) {
    var ledger by remember { mutableStateOf(runCatching { store.read() }.getOrNull()) }
    var screen by rememberSaveable { mutableStateOf(AppScreen.OVERVIEW) }
    var notice by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val backups = remember(context) { AndroidBackupManager(context) }
    val runAction = rememberActionRunner(backups, { ledger = store.read() }) { notice = it }
    if (ledger == null) return SetupScreen { currency ->
        runAction {
            val created = Ledger(UUID.randomUUID().toString(), currency, 2)
            store.replace(created)
            store.writeTransferState(TransferState(revision = created.revision))
        }
    }
    val current = ledger ?: return
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = { Text("cofrinho.", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp, 16.dp)) }, bottomBar = { AppNavigation(screen) { screen = it } }) { padding ->
            Column(Modifier.padding(padding).padding(20.dp)) { ScreenContent(screen, current, store, backups, runAction) { ledger = store.read() } }
        }
    }
    notice?.let { ErrorDialog(it) { notice = null } }
}

@Composable
private fun rememberActionRunner(backups: AndroidBackupManager, refresh: () -> Unit, report: (String?) -> Unit): ActionRunner {
    val scope = rememberCoroutineScope()
    return { action -> scope.launch(Dispatchers.IO) { val result = runCatching { action(); backups.afterFinancialEdit() }; withContext(Dispatchers.Main) { report(result.exceptionOrNull()?.message); refresh() } } }
}

@Composable
private fun ScreenContent(screen: AppScreen, ledger: Ledger, store: AndroidLedgerStore, backups: AndroidBackupManager, runAction: ActionRunner, refresh: () -> Unit) {
    when (screen) {
        AppScreen.OVERVIEW -> OverviewScreen(ledger, store, runAction)
        AppScreen.EXPENSES -> ExpensesScreen(ledger, store, runAction)
        AppScreen.RECURRING -> RecurringScreen(ledger, store, runAction)
        AppScreen.INCOME -> IncomeScreen(ledger, store, runAction)
        AppScreen.TRANSFER -> TransferScreen(store, backups, refresh)
    }
}

@Composable
private fun SetupScreen(onCreate: (String) -> Unit) {
    var currency by rememberSaveable { mutableStateOf("BRL") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Set up your ledger", style = MaterialTheme.typography.headlineMedium)
        Text("Choose one currency. This ledger keeps it permanently.")
        OutlinedTextField(currency, { currency = it.uppercase().take(3) }, label = { Text("Currency code") }, singleLine = true)
        Button(onClick = { onCreate(currency) }, enabled = currency.matches(Regex("[A-Z]{3}"))) { Text("Create ledger") }
    }
}

@Composable
private fun OverviewScreen(ledger: Ledger, store: AndroidLedgerStore, runAction: ActionRunner) {
    var showAdd by remember { mutableStateOf(false) }
    var month by rememberSaveable { mutableStateOf(LocalDate.now().toString().take(7)) }
    val summary = LedgerService(store, AndroidClock, AndroidIds).summary(month)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { TextButton(onClick = { month = shiftMonth(month, -1) }) { Text("‹") }; Text(month); TextButton(onClick = { month = shiftMonth(month, 1) }) { Text("›") } }
        Text("Available", style = MaterialTheme.typography.labelMedium)
        Text(formatMoney(summary.availableMinor, ledger.currencyScale), style = MaterialTheme.typography.displaySmall)
        if (summary.budgetMinor == 0L) Text("No budget defined")
        if (summary.availableMinor < 0L) Text("Overcommitted", color = MaterialTheme.colorScheme.error)
        Text("Income ${formatMoney(summary.budgetMinor, ledger.currencyScale)} · recurring ${formatMoney(summary.recurringMinor, ledger.currencyScale)} · variable ${formatMoney(summary.spentMinor, ledger.currencyScale)}")
        val progress = if (summary.budgetMinor > 0) (summary.usedMinor.toDouble() / summary.budgetMinor).toFloat().coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        Text("Used ${formatMoney(summary.usedMinor, ledger.currencyScale)} · Credit ${formatMoney(summary.creditSpentMinor, ledger.currencyScale)} · Debit ${formatMoney(summary.debitSpentMinor, ledger.currencyScale)}")
        summary.dailySpending.forEach { day -> Text("${day.date}: ${formatMoney(day.amountMinor, ledger.currencyScale)}") }
        Button(onClick = { showAdd = true }) { Text("Add expense") }
    }
    if (showAdd) ExpenseDialog(ledger.currencyScale, { showAdd = false }) { amount, date, method, description -> runAction { LedgerService(store, AndroidClock, AndroidIds).addExpense(amount, date, method, description) }; showAdd = false }
}

@Composable
private fun ExpensesScreen(ledger: Ledger, store: AndroidLedgerStore, runAction: ActionRunner) {
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<cofrinho.finance.Expense?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Variable expenses", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { showAdd = true }) { Text("Add expense") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ledger.expenses) { expense ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${expense.occurredOn} · ${formatMoney(expense.amountMinor, ledger.currencyScale)} · ${expense.paymentMethod.name.lowercase()} · ${expense.description.orEmpty()}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editTarget = expense }) { Text("Edit") }
                        TextButton(onClick = { runAction { LedgerService(store, AndroidClock, AndroidIds).deleteExpense(expense.id) } }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (showAdd) ExpenseDialog(ledger.currencyScale, { showAdd = false }) { amount, date, method, description -> runAction { LedgerService(store, AndroidClock, AndroidIds).addExpense(amount, date, method, description) }; showAdd = false }
    editTarget?.let { expense -> ExpenseDialog(ledger.currencyScale, { editTarget = null }, expense) { amount, date, method, description -> runAction { LedgerService(store, AndroidClock, AndroidIds).editExpense(expense.id, amount, date, method, description) }; editTarget = null } }
}

@Composable
private fun RecurringScreen(ledger: Ledger, store: AndroidLedgerStore, runAction: ActionRunner) {
    var showAdd by remember { mutableStateOf(false) }
    var changeTarget by remember { mutableStateOf<RecurringExpense?>(null) }
    var overrideTarget by remember { mutableStateOf<RecurringExpense?>(null) }
    var month by rememberSaveable { mutableStateOf(LocalDate.now().toString().take(7)) }
    val service = LedgerService(store, AndroidClock, AndroidIds)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Recurring expenses", style = MaterialTheme.typography.headlineMedium)
        Text("Register once. The amount reduces every active month.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { month = shiftMonth(month, -1) }) { Text("‹") }; Text(month); TextButton(onClick = { month = shiftMonth(month, 1) }) { Text("›") } }
        Button(onClick = { showAdd = true }) { Text("Add recurring expense") }
        service.resolvedRecurringExpenses(month).forEach { resolved ->
            Text("${resolved.name} · ${formatMoney(resolved.amountMinor, ledger.currencyScale)} / month")
            ledger.recurringExpenses.find { it.id == resolved.id }?.let { expense -> Row { TextButton(onClick = { changeTarget = expense }) { Text("Change") }; TextButton(onClick = { overrideTarget = expense }) { Text("This month") }; TextButton(onClick = { runAction { service.stopRecurringExpense(expense.id, shiftMonth(month, 1)) } }) { Text("Stop") } } }
        }
    }
    if (showAdd) RecurringDialog(ledger.currencyScale, { showAdd = false }) { name, amount, from, method -> runAction { service.addRecurringExpense(name, amount, from, method) }; showAdd = false }
    changeTarget?.let { expense -> RecurringChangeDialog(ledger, expense, month, { changeTarget = null }) { amount, from, method -> runAction { service.changeRecurringExpense(expense.id, amount, from, method) }; changeTarget = null } }
    overrideTarget?.let { expense -> RecurringOverrideDialog(ledger.currencyScale, expense, { overrideTarget = null }) { selectedMonth, amount -> runAction { service.overrideRecurringExpense(expense.id, selectedMonth, amount) }; overrideTarget = null } }
}

@Composable
private fun IncomeScreen(ledger: Ledger, store: AndroidLedgerStore, runAction: ActionRunner) {
    var showIncome by remember { mutableStateOf(false) }
    var changeTarget by remember { mutableStateOf<IncomeSource?>(null) }
    var overrideTarget by remember { mutableStateOf<IncomeSource?>(null) }
    var month by rememberSaveable { mutableStateOf(LocalDate.now().toString().take(7)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recurring income", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { month = shiftMonth(month, -1) }) { Text("‹") }; Text(month); TextButton(onClick = { month = shiftMonth(month, 1) }) { Text("›") } }
        Button(onClick = { showIncome = true }) { Text("Add income source") }
        ledger.sources.forEach { source ->
            val schedule = ledger.schedules.filter { it.sourceId == source.id && it.startsMonth <= month }.maxByOrNull { it.startsMonth }
            Text("${source.name} · ${schedule?.let { formatMoney(it.amountMinor, ledger.currencyScale) } ?: "No schedule"} / month")
            Row { TextButton(onClick = { changeTarget = source }) { Text("Change") }; TextButton(onClick = { overrideTarget = source }) { Text("This month") }; TextButton(onClick = { runAction { LedgerService(store, AndroidClock, AndroidIds).stopIncome(source.id, shiftMonth(month, 1)) } }) { Text("Stop") } }
        }
    }
    if (showIncome) IncomeDialog(ledger.currencyScale, { showIncome = false }) { name, amount, from -> runAction { LedgerService(store, AndroidClock, AndroidIds).addIncomeSource(name, amount, from) }; showIncome = false }
    changeTarget?.let { source -> ChangeIncomeDialog(ledger, source, month, { changeTarget = null }) { amount, from -> runAction { LedgerService(store, AndroidClock, AndroidIds).changeRecurringIncome(source.id, amount, from) }; changeTarget = null } }
    overrideTarget?.let { source -> OverrideIncomeDialog(ledger.currencyScale, source, { overrideTarget = null }) { month, amount -> runAction { LedgerService(store, AndroidClock, AndroidIds).overrideIncome(source.id, month, amount) }; overrideTarget = null } }
}

@Composable
private fun TransferScreen(store: AndroidLedgerStore, backups: AndroidBackupManager, refresh: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { AndroidTransferController(context, store, backups) }
    var notice by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.cofrinho+json")) { uri -> if (uri != null) scope.launch(Dispatchers.IO) { val result = runCatching { controller.export(uri) }; withContext(Dispatchers.Main) { notice = result.exceptionOrNull()?.message ?: "Ledger exported; editing is handed off." } } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch(Dispatchers.IO) { val result = runCatching { controller.preview(uri) }; withContext(Dispatchers.Main) { notice = result.exceptionOrNull()?.message ?: "Import ready; use the CLI for explicit conflict choice." } } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Transfer", style = MaterialTheme.typography.headlineMedium); Text("Use format 2 .cofrinho files between phone and CLI."); Button(onClick = { exportLauncher.launch("cofrinho-ledger.cofrinho") }) { Text("Export ledger") }; Button(onClick = { importLauncher.launch(arrayOf("application/*", "text/*", "*/*")) }) { Text("Preview import") }; if (store.readTransferState().handedOff) Button(onClick = { controller.resumeEditing(); refresh() }) { Text("Resume editing") } }
    notice?.let { ErrorDialog(it) { notice = null } }
}

@Composable
private fun ExpenseDialog(scale: Int, dismiss: () -> Unit, existing: cofrinho.finance.Expense? = null, save: (Long, String, PaymentMethod, String?) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(existing?.let { MoneyRules.formatMinor(it.amountMinor, scale) }.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(existing?.occurredOn ?: AndroidClock.today()) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var method by rememberSaveable { mutableStateOf(existing?.paymentMethod ?: PaymentMethod.CREDIT) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "Add expense" else "Edit expense") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { NumericField(amount, { amount = it }, "Amount", scale); OutlinedTextField(date, { date = it }, label = { Text("Purchase date") }, singleLine = true); OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true); PaymentPicker(method) { method = it }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(it, date, method, description.takeIf(String::isNotBlank)) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun RecurringDialog(scale: Int, dismiss: () -> Unit, save: (String, Long, String, PaymentMethod) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }; var amount by rememberSaveable { mutableStateOf("") }; var from by rememberSaveable { mutableStateOf(AndroidClock.today().take(7)) }; var method by rememberSaveable { mutableStateOf(PaymentMethod.CREDIT) }; var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add recurring expense") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Name") }); NumericField(amount, { amount = it }, "Monthly amount", scale); OutlinedTextField(from, { from = it }, label = { Text("Starting month") }); PaymentPicker(method) { method = it }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(name, it, from, method) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun RecurringChangeDialog(ledger: Ledger, expense: RecurringExpense, selectedMonth: String, dismiss: () -> Unit, save: (Long, String, PaymentMethod?) -> Unit) {
    val current = ledger.recurringExpenseSchedules.filter { it.recurringExpenseId == expense.id && it.startsMonth <= selectedMonth }.maxByOrNull { it.startsMonth }
    var amount by rememberSaveable { mutableStateOf(current?.let { MoneyRules.formatMinor(it.amountMinor, ledger.currencyScale) }.orEmpty()) }; var from by rememberSaveable { mutableStateOf(selectedMonth) }; var method by rememberSaveable { mutableStateOf(current?.paymentMethod ?: PaymentMethod.CREDIT) }; var methodTouched by rememberSaveable { mutableStateOf(false) }; var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Change ${expense.name}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { NumericField(amount, { amount = it }, "Monthly amount", ledger.currencyScale); OutlinedTextField(from, { from = it }, label = { Text("Effective month") }); PaymentPicker(method) { method = it; methodTouched = true }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, ledger.currencyScale) }.onSuccess { save(it, from, method.takeIf { methodTouched }) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun RecurringOverrideDialog(scale: Int, expense: RecurringExpense, dismiss: () -> Unit, save: (String, Long) -> Unit) {
    var month by rememberSaveable { mutableStateOf(AndroidClock.today().take(7)) }; var amount by rememberSaveable { mutableStateOf("") }; var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Change ${expense.name} for one month") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(month, { month = it }, label = { Text("Budget month") }); NumericField(amount, { amount = it }, "Amount, zero skips", scale); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(month, it) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun IncomeDialog(scale: Int, dismiss: () -> Unit, save: (String, Long, String) -> Unit) { var name by rememberSaveable { mutableStateOf("") }; var amount by rememberSaveable { mutableStateOf("") }; var from by rememberSaveable { mutableStateOf(AndroidClock.today().take(7)) }; var error by rememberSaveable { mutableStateOf<String?>(null) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Register recurring income") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Source") }); NumericField(amount, { amount = it }, "Monthly amount", scale); OutlinedTextField(from, { from = it }, label = { Text("Starting month") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(name, it, from) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable
private fun ChangeIncomeDialog(ledger: Ledger, source: IncomeSource, selectedMonth: String, dismiss: () -> Unit, save: (Long, String) -> Unit) { val schedule = ledger.schedules.filter { it.sourceId == source.id && it.startsMonth <= selectedMonth }.maxByOrNull { it.startsMonth }; var amount by rememberSaveable { mutableStateOf(schedule?.let { MoneyRules.formatMinor(it.amountMinor, ledger.currencyScale) }.orEmpty()) }; var from by rememberSaveable { mutableStateOf(selectedMonth) }; var error by rememberSaveable { mutableStateOf<String?>(null) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Change ${source.name}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { NumericField(amount, { amount = it }, "Monthly amount", ledger.currencyScale); OutlinedTextField(from, { from = it }, label = { Text("Effective month") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, ledger.currencyScale) }.onSuccess { save(it, from) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable
private fun OverrideIncomeDialog(scale: Int, source: IncomeSource, dismiss: () -> Unit, save: (String, Long) -> Unit) { var month by rememberSaveable { mutableStateOf(AndroidClock.today().take(7)) }; var amount by rememberSaveable { mutableStateOf("") }; var error by rememberSaveable { mutableStateOf<String?>(null) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Change ${source.name} for one month") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(month, { month = it }, label = { Text("Budget month") }); NumericField(amount, { amount = it }, "Amount, zero skips", scale); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(month, it) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable
private fun NumericField(value: String, change: (String) -> Unit, label: String, scale: Int = 6) { val pattern = if (scale == 0) "\\d+" else "\\d+(\\.\\d{0,$scale})?"; OutlinedTextField(value, { if (it.isEmpty() || it.matches(Regex(pattern))) change(it) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true) }

@Composable
private fun PaymentPicker(method: PaymentMethod, select: (PaymentMethod) -> Unit) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(method == PaymentMethod.CREDIT, { select(PaymentMethod.CREDIT) }, label = { Text("Credit") }); FilterChip(method == PaymentMethod.DEBIT, { select(PaymentMethod.DEBIT) }, label = { Text("Debit") }) } }
@Composable
private fun ErrorDialog(message: String, dismiss: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text("Cofrinho") }, text = { Text(message) }, confirmButton = { TextButton(onClick = dismiss) { Text("OK") } }) }
@Composable
private fun AppNavigation(current: AppScreen, select: (AppScreen) -> Unit) { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) { AppScreen.entries.forEach { screen -> TextButton(onClick = { select(screen) }) { Text(if (screen == current) "• ${screen.label}" else screen.label) } } } }

private enum class AppScreen(val label: String) { OVERVIEW("Overview"), EXPENSES("Expenses"), RECURRING("Recurring"), INCOME("Income"), TRANSFER("Transfer") }
private object AndroidClock : LedgerClock { override fun today(): String = LocalDate.now().toString(); override fun nowUtc(): String = Instant.now().toString() }
private object AndroidIds : IdentifierSource { override fun next(): String = UUID.randomUUID().toString() }
private fun formatMoney(value: Long, scale: Int): String = MoneyRules.formatMinor(value, scale)
private fun shiftMonth(month: String, offset: Long): String = java.time.YearMonth.parse(month).plusMonths(offset).toString()
