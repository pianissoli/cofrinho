package caixa.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import caixa.finance.IdentifierSource
import caixa.finance.IncomeSource
import caixa.finance.Ledger
import caixa.finance.LedgerClock
import caixa.finance.LedgerService
import caixa.finance.MoneyRules
import caixa.finance.PaymentMethod
import caixa.finance.TransferState
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
        setContent { CaixaApp(AndroidLedgerStore(this)) }
    }
}

@Composable
private fun CaixaApp(store: AndroidLedgerStore) {
    var ledger by remember { mutableStateOf(runCatching { store.read() }.getOrNull()) }
    var screen by rememberSaveable { mutableStateOf(AppScreen.OVERVIEW) }
    var notice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
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
    MaterialTheme(colorScheme = darkColorScheme()) { AppContent(current, store, backups, screen, runAction, { screen = it }) { ledger = store.read() } }
    notice?.let { message -> ErrorDialog(message) { notice = null } }
}

@Composable
private fun rememberActionRunner(backups: AndroidBackupManager, refresh: () -> Unit, report: (String?) -> Unit): ActionRunner {
    val scope = rememberCoroutineScope()
    return { action ->
        scope.launch(Dispatchers.IO) {
            val result = runCatching { action(); backups.afterFinancialEdit() }
            withContext(Dispatchers.Main) { result.exceptionOrNull()?.let { report(it.message) }; refresh() }
        }
    }
}

@Composable
private fun AppContent(ledger: Ledger, store: AndroidLedgerStore, backups: AndroidBackupManager, screen: AppScreen, runAction: ActionRunner, select: (AppScreen) -> Unit, refresh: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(topBar = { Text("caixa.", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp, 16.dp)) }, bottomBar = { AppNavigation(screen, select) }) { padding ->
            Column(modifier = Modifier.padding(padding).padding(20.dp)) { ScreenContent(screen, ledger, store, backups, runAction, refresh) }
        }
    }
}

@Composable
private fun ScreenContent(screen: AppScreen, ledger: Ledger, store: AndroidLedgerStore, backups: AndroidBackupManager, runAction: ActionRunner, refresh: () -> Unit) {
    when (screen) {
        AppScreen.OVERVIEW -> OverviewScreen(ledger, store, runAction)
        AppScreen.EXPENSES -> ExpensesScreen(ledger, store, runAction)
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
    var showPlan by remember { mutableStateOf(false) }
    var month by rememberSaveable { mutableStateOf(LocalDate.now().toString().take(7)) }
    val summary = LedgerService(store, AndroidClock, AndroidIds).summary(month)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { month = shiftMonth(month, -1) }) { Text("‹") }
            Text(month, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { month = shiftMonth(month, 1) }) { Text("›") }
        }
        Text("Available after planned expenses", style = MaterialTheme.typography.labelMedium)
        Text(formatMoney(summary.availableMinor, ledger.currencyScale), style = MaterialTheme.typography.displaySmall)
        if (summary.budgetMinor == 0L) Text("No budget defined")
        if (summary.availableMinor < 0L) Text("Overcommitted", color = MaterialTheme.colorScheme.error)
        Text("Budget ${formatMoney(summary.budgetMinor, ledger.currencyScale)} · spent ${formatMoney(summary.spentMinor, ledger.currencyScale)} · reserved ${formatMoney(summary.reservedMinor, ledger.currencyScale)}")
        val progress = if (summary.budgetMinor > 0) (summary.spentMinor.toDouble() / summary.budgetMinor).toFloat().coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        Text("Credit ${formatMoney(summary.creditSpentMinor, ledger.currencyScale)} · Debit ${formatMoney(summary.debitSpentMinor, ledger.currencyScale)}")
        summary.dailySpending.forEach { day -> Text("${day.date}: ${formatMoney(day.amountMinor, ledger.currencyScale)}") }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { showAdd = true }) { Text("Add expense") }
            Button(onClick = { showPlan = true }) { Text("Plan expense") }
        }
        Text("Income repeats monthly after one registration. New expenses use today by default.")
    }
    if (showAdd) ExpenseDialog(ledger.currencyScale, false, { showAdd = false }) { amount, date, method ->
        runAction { LedgerService(store, AndroidClock, AndroidIds).addExpense(amount, date, method) }
        showAdd = false
    }
    if (showPlan) ExpenseDialog(ledger.currencyScale, true, { showPlan = false }) { amount, date, method ->
        runAction { LedgerService(store, AndroidClock, AndroidIds).planExpense(amount, date, method) }
        showPlan = false
    }
}

@Composable
private fun ExpensesScreen(ledger: Ledger, store: AndroidLedgerStore, runAction: ActionRunner) {
    var showAdd by remember { mutableStateOf(false) }
    var recordTarget by remember { mutableStateOf<caixa.finance.Expense?>(null) }
    var reopenTarget by remember { mutableStateOf<caixa.finance.Expense?>(null) }
    var editTarget by remember { mutableStateOf<caixa.finance.Expense?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Expenses", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { showAdd = true }) { Text("Add expense") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ledger.expenses) { expense ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${expense.status.name.lowercase()} · ${formatMoney(expense.amountMinor, ledger.currencyScale)} · ${expense.description.orEmpty()}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (expense.status != caixa.finance.ExpenseStatus.CANCELLED) TextButton(onClick = { editTarget = expense }) { Text("Edit") }
                        if (expense.status == caixa.finance.ExpenseStatus.PLANNED) {
                            TextButton(onClick = { recordTarget = expense }) { Text("Record") }
                            TextButton(onClick = { runAction { LedgerService(store, AndroidClock, AndroidIds).cancelPlan(expense.id) } }) { Text("Cancel") }
                        }
                        if (expense.status == caixa.finance.ExpenseStatus.RECORDED) {
                            TextButton(onClick = { reopenTarget = expense }) { Text("Reopen") }
                            TextButton(onClick = { runAction { LedgerService(store, AndroidClock, AndroidIds).deleteExpense(expense.id) } }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) ExpenseDialog(ledger.currencyScale, false, { showAdd = false }) { amount, date, method ->
        runAction { LedgerService(store, AndroidClock, AndroidIds).addExpense(amount, date, method) }
        showAdd = false
    }
    recordTarget?.let { target -> RecordDialog(ledger.currencyScale, target, { recordTarget = null }) { amount, date, method ->
        runAction { LedgerService(store, AndroidClock, AndroidIds).recordPlanned(target.id, amount, date, method) }
        recordTarget = null
    } }
    reopenTarget?.let { target -> ReopenDialog(ledger.currencyScale, target, { reopenTarget = null }) { amount, date ->
        runAction { LedgerService(store, AndroidClock, AndroidIds).reopenRecordedExpense(target.id, amount, date) }
        reopenTarget = null
    } }
    editTarget?.let { target -> EditExpenseDialog(ledger.currencyScale, target, { editTarget = null }) { amount, date, method ->
        runAction {
            val service = LedgerService(store, AndroidClock, AndroidIds)
            if (target.status == caixa.finance.ExpenseStatus.PLANNED) service.editPlannedExpense(target.id, amount, date, method) else service.editExpense(target.id, amount, date, method)
        }
        editTarget = null
    } }
}

@Composable
private fun IncomeScreen(ledger: Ledger, store: AndroidLedgerStore, runAction: ActionRunner) {
    var showIncome by remember { mutableStateOf(false) }
    var changeTarget by remember { mutableStateOf<IncomeSource?>(null) }
    var overrideTarget by remember { mutableStateOf<IncomeSource?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recurring income", style = MaterialTheme.typography.headlineMedium)
        Text("Register each source once. It resolves automatically each month.")
        Button(onClick = { showIncome = true }) { Text("Add income source") }
        ledger.sources.forEach { source -> IncomeRow(source, ledger, { changeTarget = source }, { overrideTarget = source }) { runAction { LedgerService(store, AndroidClock, AndroidIds).stopIncome(source.id, LocalDate.now().plusMonths(1).toString().take(7)) } } }
    }
    if (showIncome) IncomeDialog(ledger.currencyScale, { showIncome = false }) { name, amount, from ->
        runAction { LedgerService(store, AndroidClock, AndroidIds).addIncomeSource(name, amount, from) }
        showIncome = false
    }
    changeTarget?.let { source -> ChangeIncomeDialog(ledger, source, { changeTarget = null }) { amount, from ->
        runAction { LedgerService(store, AndroidClock, AndroidIds).changeRecurringIncome(source.id, amount, from) }
        changeTarget = null
    } }
    overrideTarget?.let { source -> OverrideIncomeDialog(ledger, source, { overrideTarget = null }) { month, amount ->
        runAction { LedgerService(store, AndroidClock, AndroidIds).overrideIncome(source.id, month, amount) }
        overrideTarget = null
    } }
}

@Composable
private fun IncomeRow(source: IncomeSource, ledger: Ledger, change: () -> Unit, override: () -> Unit, archive: () -> Unit) {
    val schedule = ledger.schedules.filter { it.sourceId == source.id }.maxByOrNull { it.startsMonth }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${source.name} · ${schedule?.let { formatMoney(it.amountMinor, ledger.currencyScale) } ?: "No schedule"} / month")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = change) { Text("Change") }
            TextButton(onClick = override) { Text("This month") }
            TextButton(onClick = archive) { Text("Stop next month") }
        }
    }
}

@Composable
private fun TransferScreen(store: AndroidLedgerStore, backups: AndroidBackupManager, onLedgerChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { AndroidTransferController(context, store, backups) }
    var pending by remember { mutableStateOf<PendingAndroidImport?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.caixa+json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching { controller.export(uri) }
            withContext(Dispatchers.Main) { notice = result.exceptionOrNull()?.message ?: "Ledger exported. This device is read-only until the ledger returns." }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching { controller.preview(uri) }
            withContext(Dispatchers.Main) { result.onSuccess { pending = it }.onFailure { notice = it.message } }
        }
    }
    val state = store.readTransferState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Transfer", style = MaterialTheme.typography.headlineMedium)
        Text(if (state.handedOff) "Read-only: editing is handed to another device." else "Editing on this phone.")
        Text("Export a .caixa file to the Linux CLI, then import the returned file here.")
        Text("Conflicts show both ledgers and require an explicit whole-ledger choice.")
        Text("Exports are plaintext and contain financial information.")
        Button(onClick = { exportLauncher.launch("caixa-ledger.caixa") }, enabled = !state.handedOff || state.stagedPayload != null) { Text(if (state.handedOff) "Retry export" else "Export ledger") }
        Button(onClick = { importLauncher.launch(arrayOf("application/*", "text/*", "*/*")) }) { Text("Import ledger") }
        Button(onClick = { scope.launch(Dispatchers.IO) { val result = runCatching { backups.afterFinancialEdit() }; withContext(Dispatchers.Main) { notice = result.exceptionOrNull()?.message ?: "Daily backup complete" } } }) { Text("Retry local backup") }
        if (state.handedOff) Button(onClick = { controller.resumeEditing(); onLedgerChanged() }) { Text("Resume editing here") }
    }
    pending?.let { import -> ImportReview(import, controller, onLedgerChanged) { pending = null } }
    notice?.let { message -> ErrorDialog(message) { notice = null } }
}

@Composable
private fun ImportReview(pending: PendingAndroidImport, controller: AndroidTransferController, onLedgerChanged: () -> Unit, dismiss: () -> Unit) {
    val conflict = pending.inspection.decision == caixa.transfer.ImportDecision.CONFLICT
    val summary = pending.inspection.incoming
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (conflict) "Choose a ledger" else "Review import") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (conflict) "Both devices changed. Nothing is replaced until you decide." else pending.inspection.reason)
            Text("${summary.sources.size} income sources · ${summary.expenses.size} expenses · ${summary.currencyCode}")
            Text("Use the incoming version only after reviewing its complete ledger.")
        }
    }, confirmButton = { TextButton(onClick = { controller.apply(pending, true); onLedgerChanged(); dismiss() }) { Text(if (conflict) "Use imported" else "Import") } }, dismissButton = { TextButton(onClick = { if (conflict) { controller.preserveConflict(pending) }; dismiss() }) { Text(if (conflict) "Keep local" else "Cancel") } })
}

@Composable
private fun ExpenseDialog(scale: Int, planned: Boolean, dismiss: () -> Unit, save: (Long, String, PaymentMethod) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(AndroidClock.today()) }
    var dateEdited by rememberSaveable { mutableStateOf(false) }
    var method by rememberSaveable { mutableStateOf(PaymentMethod.CREDIT) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (planned) "Plan expense" else "Add expense") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true)
            OutlinedTextField(date, { date = it; dateEdited = true }, label = { Text(if (planned) "Expected date" else "Purchase date") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(method == PaymentMethod.CREDIT, { method = PaymentMethod.CREDIT }, label = { Text("Credit") })
                FilterChip(method == PaymentMethod.DEBIT, { method = PaymentMethod.DEBIT }, label = { Text("Debit") })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(it, if (dateEdited) date else AndroidClock.today(), method) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun RecordDialog(scale: Int, expense: caixa.finance.Expense, dismiss: () -> Unit, save: (Long, String, PaymentMethod) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(MoneyRules.formatMinor(expense.amountMinor, scale)) }
    var date by rememberSaveable { mutableStateOf(AndroidClock.today()) }
    var dateEdited by rememberSaveable { mutableStateOf(false) }
    var method by rememberSaveable { mutableStateOf(expense.paymentMethod) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Record planned expense") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amount, { amount = it }, label = { Text("Actual amount") }, singleLine = true)
            OutlinedTextField(date, { date = it; dateEdited = true }, label = { Text("Purchase date") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(method == PaymentMethod.CREDIT, { method = PaymentMethod.CREDIT }, label = { Text("Credit") })
                FilterChip(method == PaymentMethod.DEBIT, { method = PaymentMethod.DEBIT }, label = { Text("Debit") })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(it, if (dateEdited) date else AndroidClock.today(), method) }.onFailure { error = it.message } }) { Text("Record") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun EditExpenseDialog(scale: Int, expense: caixa.finance.Expense, dismiss: () -> Unit, save: (Long, String, PaymentMethod) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(MoneyRules.formatMinor(expense.amountMinor, scale)) }
    var date by rememberSaveable { mutableStateOf(expense.occurredOn ?: expense.expectedOn ?: AndroidClock.today()) }
    var method by rememberSaveable { mutableStateOf(expense.paymentMethod) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Edit expense") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true)
            OutlinedTextField(date, { date = it }, label = { Text(if (expense.status == caixa.finance.ExpenseStatus.PLANNED) "Expected date" else "Purchase date") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(method == PaymentMethod.CREDIT, { method = PaymentMethod.CREDIT }, label = { Text("Credit") })
                FilterChip(method == PaymentMethod.DEBIT, { method = PaymentMethod.DEBIT }, label = { Text("Debit") })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(it, date, method) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun ReopenDialog(scale: Int, expense: caixa.finance.Expense, dismiss: () -> Unit, save: (Long, String) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(MoneyRules.formatMinor(expense.amountMinor, scale)) }
    var date by rememberSaveable { mutableStateOf(expense.expectedOn ?: AndroidClock.today()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Reopen as planned") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amount, { amount = it }, label = { Text("Expected amount") }, singleLine = true)
            OutlinedTextField(date, { date = it }, label = { Text("Expected date") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(it, date) }.onFailure { error = it.message } }) { Text("Reopen") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun IncomeDialog(scale: Int, dismiss: () -> Unit, save: (String, Long, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf(LocalDate.now().toString().take(7)) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Register recurring income") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Source") })
            OutlinedTextField(amount, { amount = it }, label = { Text("Monthly amount") })
            OutlinedTextField(from, { from = it }, label = { Text("Starting month") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, scale) }.onSuccess { save(name, it, from) }.onFailure { error = it.message } }) { Text("Register") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun ChangeIncomeDialog(ledger: Ledger, source: IncomeSource, dismiss: () -> Unit, save: (Long, String) -> Unit) {
    val schedule = ledger.schedules.filter { it.sourceId == source.id }.maxByOrNull { it.startsMonth }
    var amount by rememberSaveable { mutableStateOf(schedule?.let { MoneyRules.formatMinor(it.amountMinor, ledger.currencyScale) }.orEmpty()) }
    var from by rememberSaveable { mutableStateOf(LocalDate.now().toString().take(7)) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Change ${source.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amount, { amount = it }, label = { Text("Monthly amount") }, singleLine = true)
            OutlinedTextField(from, { from = it }, label = { Text("Effective month") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, ledger.currencyScale) }.onSuccess { save(it, from) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun OverrideIncomeDialog(ledger: Ledger, source: IncomeSource, dismiss: () -> Unit, save: (String, Long) -> Unit) {
    var month by rememberSaveable { mutableStateOf(LocalDate.now().toString().take(7)) }
    var amount by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Change ${source.name} for one month") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(month, { month = it }, label = { Text("Budget month") }, singleLine = true)
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount, zero skips") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { MoneyRules.parseMinor(amount, ledger.currencyScale) }.onSuccess { save(month, it) }.onFailure { error = it.message } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun ErrorDialog(message: String, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Caixa") }, text = { Text(message) }, confirmButton = { TextButton(onClick = dismiss) { Text("OK") } })
}

@Composable
private fun AppNavigation(current: AppScreen, select: (AppScreen) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        AppScreen.entries.forEach { screen -> TextButton(onClick = { select(screen) }) { Text(if (screen == current) "• ${screen.label}" else screen.label) } }
    }
}

private enum class AppScreen(val label: String) { OVERVIEW("Overview"), EXPENSES("Expenses"), INCOME("Income"), TRANSFER("Transfer") }

private object AndroidClock : LedgerClock {
    override fun today(): String = LocalDate.now().toString()
    override fun nowUtc(): String = Instant.now().toString()
}

private object AndroidIds : IdentifierSource { override fun next(): String = UUID.randomUUID().toString() }

private fun formatMoney(value: Long, scale: Int): String = MoneyRules.formatMinor(value, scale)

private fun shiftMonth(month: String, offset: Long): String = java.time.YearMonth.parse(month).plusMonths(offset).toString()
