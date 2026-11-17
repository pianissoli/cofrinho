package cofrinho.cli

import cofrinho.cli.tui.RecurringDraft
import cofrinho.cli.tui.RecurringFormMode
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurringDraftTest {
    @Test
    fun typingReplacesPrefilledAmountsInChangeAndOverrideForms() {
        for (mode in listOf(RecurringFormMode.CHANGE, RecurringFormMode.OVERRIDE)) {
            val draft = RecurringDraft(mode, "rent", "2026-11", "100.00")
            "25.50".forEach { draft.append(it, 2) }
            assertEquals("25.50", draft.amount)
        }
    }

    @Test
    fun invalidCharactersKeepPrefilledAmountAndZeroCanReplaceIt() {
        val draft = RecurringDraft(RecurringFormMode.OVERRIDE, "rent", "2026-11", "100.00")
        "bad-".forEach { draft.append(it, 2) }
        assertEquals("100.00", draft.amount)
        draft.append('0', 2)
        assertEquals("0", draft.amount)
    }

    @Test
    fun backspaceEditsExistingAmountInsteadOfReplacingIt() {
        val draft = RecurringDraft(RecurringFormMode.OVERRIDE, "rent", "2026-11", "100.00")
        draft.backspace()
        draft.append('5', 2)
        assertEquals("100.05", draft.amount)
    }

    @Test
    fun editingMonthDoesNotPreventReplacingAmount() {
        val draft = RecurringDraft(RecurringFormMode.OVERRIDE, "rent", "2026-11", "100.00")
        draft.focus = 1
        draft.backspace()
        draft.append('2', 2)
        draft.focus = 0
        draft.append('5', 2)
        assertEquals("2026-12", draft.fromMonth)
        assertEquals("5", draft.amount)
    }
}
