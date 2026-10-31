"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");
const {
  previewLocalDate,
  fillPreviewCurrentDates,
  bindPreviewDateDefaults,
  readPreviewClock,
} = require("./mobile-preview.js");

class FakeDateField {
  /** @param {string} type @param {string} value */
  constructor(type, value = "") {
    this.type = type;
    this.value = value;
  }
}

class FakeDateSheet extends EventTarget {
  /** @param {FakeDateField[]} fields */
  constructor(fields) {
    super();
    this.fields = fields;
  }

  /** @param {string} selector @returns {FakeDateField[]} */
  querySelectorAll(selector) {
    assert.equal(selector, "input[data-default-today]");
    return this.fields;
  }
}

class FakeDateDocument {
  /** @param {FakeDateSheet[]} sheets */
  constructor(sheets) {
    this.sheets = sheets;
  }

  /** @param {string} selector @returns {FakeDateSheet[]} */
  querySelectorAll(selector) {
    assert.equal(selector, "[data-today-on-open]");
    return this.sheets;
  }
}

class FakeLocalClock {
  /** @param {Date} instant */
  constructor(instant) {
    this.instant = instant;
  }

  /** @returns {Date} */
  read() {
    const instant = new Date(this.instant.getTime());
    return instant;
  }
}

test("formats and pads local calendar components", /** @returns {void} */ () => {
  const instant = new Date(2027, 0, 2, 12);
  const formatted = previewLocalDate(instant);
  assert.equal(formatted, "2027-01-02");
});

test("local dates stay correct on both sides of UTC", /** @returns {void} */ () => {
  const originalTimezone = process.env.TZ;
  try {
    for (const timezone of ["America/Sao_Paulo", "Pacific/Auckland"]) {
      process.env.TZ = timezone;
      const hour = timezone === "Pacific/Auckland" ? 0 : 23;
      const instant = new Date(2026, 9, 30, hour, 59);
      assert.equal(previewLocalDate(instant), "2026-10-30");
      assert.notEqual(instant.toISOString().slice(0, 10), "2026-10-30");
    }
  } finally {
    if (originalTimezone === undefined) delete process.env.TZ;
    else process.env.TZ = originalTimezone;
  }
});

test("fills fresh date and starting-month defaults", /** @returns {void} */ () => {
  const fields = [new FakeDateField("date"), new FakeDateField("month")];
  const sheet = new FakeDateSheet(fields);
  fillPreviewCurrentDates(sheet, new Date(2026, 9, 30, 12));
  assert.deepEqual(fields.map(/** @param {FakeDateField} field @returns {string} */ (field) => field.value), ["2026-10-30", "2026-10"]);
});

test("fresh openings refresh after midnight; closing preserves chosen dates", /** @returns {void} */ () => {
  const field = new FakeDateField("date");
  const sheet = new FakeDateSheet([field]);
  const clock = new FakeLocalClock(new Date(2026, 9, 30, 23, 59));
  bindPreviewDateDefaults(new FakeDateDocument([sheet]), clock.read.bind(clock));
  sheet.dispatchEvent(Object.assign(new Event("toggle"), { newState: "open" }));
  assert.equal(field.value, "2026-10-30");
  field.value = "2026-10-15";
  sheet.dispatchEvent(Object.assign(new Event("toggle"), { newState: "closed" }));
  assert.equal(field.value, "2026-10-15");
  clock.instant = new Date(2026, 9, 31, 0, 1);
  sheet.dispatchEvent(Object.assign(new Event("toggle"), { newState: "open" }));
  assert.equal(field.value, "2026-10-31");
});

test("opening one sheet leaves other sheet dates unchanged", /** @returns {void} */ () => {
  const first = new FakeDateSheet([new FakeDateField("date")]);
  const second = new FakeDateSheet([new FakeDateField("date", "2026-09-01")]);
  const clock = new FakeLocalClock(new Date(2026, 9, 30, 12));
  bindPreviewDateDefaults(new FakeDateDocument([first, second]), clock.read.bind(clock));
  first.dispatchEvent(Object.assign(new Event("toggle"), { newState: "open" }));
  assert.equal(first.fields[0].value, "2026-10-30");
  assert.equal(second.fields[0].value, "2026-09-01");
});

test("browser clock reader returns a current native date", /** @returns {void} */ () => {
  const before = Date.now();
  const instant = readPreviewClock();
  assert.ok(instant instanceof Date);
  assert.ok(instant.getTime() >= before && instant.getTime() <= Date.now());
});
