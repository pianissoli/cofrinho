"use strict";

/**
 * Format a local calendar date without shifting it through UTC.
 * @example previewLocalDate(new Date(2026, 9, 30)) === "2026-10-30"
 * @param {Date} instant
 * @returns {string}
 */
function previewLocalDate(instant) {
  const year = String(instant.getFullYear());
  const month = String(instant.getMonth() + 1).padStart(2, "0");
  const day = String(instant.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/**
 * Fill fresh-entry defaults, leaving saved expense and plan dates intact.
 * @example fillPreviewCurrentDates(expenseSheet, new Date(2026, 9, 30));
 * @param {ParentNode} scope
 * @param {Date} instant
 * @returns {void}
 */
function fillPreviewCurrentDates(scope, instant) {
  const today = previewLocalDate(instant);
  const fields = /** @type {NodeListOf<HTMLInputElement>} */ (
    scope.querySelectorAll("input[data-default-today]")
  );
  for (const field of fields) {
    field.value = field.type === "month" ? today.slice(0, 7) : today;
  }
}

/**
 * Recompute defaults on each fresh opening, including after local midnight.
 * @example bindPreviewDateDefaults(document, () => new Date());
 * @param {ParentNode} root
 * @param {() => Date} readClock
 * @returns {void}
 */
function bindPreviewDateDefaults(root, readClock) {
  for (const sheet of root.querySelectorAll("[data-today-on-open]")) {
    /** @param {Event} event @returns {void} */
    function refreshOnPreviewOpen(event) {
      const toggle = /** @type {Event & {newState?: string}} */ (event);
      if (toggle.newState !== "open") return;
      fillPreviewCurrentDates(sheet, readClock());
    }
    sheet.addEventListener("toggle", refreshOnPreviewOpen);
  }
}

/**
 * Read the browser clock at the platform entry point.
 * @example readPreviewClock().getFullYear();
 * @returns {Date}
 */
function readPreviewClock() {
  const instant = new Date();
  return instant;
}

if (typeof document !== "undefined") {
  bindPreviewDateDefaults(document, readPreviewClock);
}

if (typeof module !== "undefined") {
  module.exports = {
    previewLocalDate,
    fillPreviewCurrentDates,
    bindPreviewDateDefaults,
    readPreviewClock,
  };
}
