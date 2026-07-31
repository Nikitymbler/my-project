const POLL_MS = 750;
const RECONNECT_BASE_MS = 1000;
const EVENT_MS = 2800;
const CANVAS_W = 1080;
const CANVAS_H = 1920;
const AUTO_SCROLL_EDGE_PX = 40;
const AUTO_SCROLL_STEP_PX = 18;
/** Raster flags for CEF/TikTok LIVE Studio (copied from textures/gui/flags_hd 256×160). */
const FLAG_FORMAT = "PNG";
const FLAG_BASE = "/overlay/tiktok/flags";
const CHROMA_COLOR = "#FF00FF";

const LAYOUT_API = "/overlay/api/layout";
const LAYOUT_RESET_API = "/overlay/api/layout/reset";
const RESERVE_SETTINGS_API = "/overlay/api/runtime/reserve-settings";
const RESERVE_BATCH_MIN = 1;
const RESERVE_BATCH_MAX = 100;
const STATS_RESET = {
  wins: "/overlay/api/stats/reset-round-wins",
  points: "/overlay/api/stats/reset-score-points",
  record: "/overlay/api/stats/reset-fighter-record",
  all: "/overlay/api/stats/reset-all",
};

/** Legacy localStorage keys — used only for one-time migration to server config. */
const STORAGE_BATTLE_POS = "arenaOverlay.battle.position";
const STORAGE_TOP5_POS = "arenaOverlay.top5.position";
const STORAGE_BATTLE_VIS = "arenaOverlay.battle.visible";
const STORAGE_TOP5_VIS = "arenaOverlay.top5.visible";
const STORAGE_MIGRATED = "arenaOverlay.layout.migratedToServer";

const DEFAULT_LAYOUT = {
  version: 3,
  battle: { xRatio: 0.04, yRatio: 0.02, visible: true, scale: 1.0 },
  top5: { xRatio: 0.68, yRatio: 0.22, visible: true, scale: 1.0 },
  record: { xRatio: 0.84, yRatio: 0.08, visible: true, scale: 1.0 },
};

const FLAG_IDS = [
  "ru", "ua", "by", "kz", "lt", "pl", "il", "am", "uz", "tj",
  "ge", "kg", "tm", "md", "az", "lv", "al", "bg", "cn", "us",
];

const DENSITY_CLASSES = [
  "countries-0",
  "countries-1",
  "countries-2",
  "countries-3-4",
  "countries-5-8",
  "countries-9-12",
  "countries-13-20",
];

const params = new URLSearchParams(window.location.search);
const preview = params.get("preview") === "1";
const debug = params.get("debug") === "1";
const editMode = params.get("edit") === "1";

/** Priority: background=chroma → chroma=1 → background=transparent → transparent=1 → chroma default. */
function resolveBackgroundMode() {
  const bg = (params.get("background") || "").trim().toLowerCase();
  if (bg === "chroma") return "chroma";
  if (params.get("chroma") === "1") return "chroma";
  if (bg === "transparent") return "transparent";
  if (params.get("transparent") === "1") return "transparent";
  return "chroma";
}

const backgroundMode = resolveBackgroundMode();

const debugStats = {
  cardsCreated: 0,
  snapshotsUpdated: 0,
  flagAssetAssignments: 0,
  flagFormat: FLAG_FORMAT,
  backgroundMode,
  editMode,
  devicePixelRatio: window.devicePixelRatio || 1,
};

function debugLog(message) {
  if (!debug) return;
  console.log(`[tiktok-overlay] ${message}`, { ...debugStats });
}

document.body.classList.add(`bg-${backgroundMode}`);
document.documentElement.classList.add(`bg-${backgroundMode}`);
if (preview) {
  document.body.classList.add("preview");
}
if (editMode) {
  document.body.classList.add("edit-mode");
  document.documentElement.classList.add("edit-mode");
}
if (preview && backgroundMode === "chroma") {
  const badge = document.getElementById("capture-mode-badge");
  if (badge) {
    badge.textContent = `CAPTURE MODE: CHROMA ${CHROMA_COLOR}`;
    badge.classList.remove("hidden");
  }
}

const phaseEl = document.getElementById("phase");
const timerLabelEl = document.getElementById("timer-label");
const timerEl = document.getElementById("timer");
const remainingEl = document.getElementById("remaining");
const noteEl = document.getElementById("status-note");
const gridEl = document.getElementById("countries-grid");
const eventBanner = document.getElementById("event-banner");
const previewDiagEl = document.getElementById("preview-diag");
const workspaceEl = document.getElementById("overlay-workspace");
const battleModuleEl = document.getElementById("battle-overlay-module");
const top5ModuleEl = document.getElementById("top-five-countries-module");
const recordModuleEl = document.getElementById("record-overlay-module");
const top5ListEl = document.getElementById("top5-list");
const top5EmptyEl = document.getElementById("top5-empty");
const recordFlagEl = document.getElementById("record-flag");
const recordValueEl = document.getElementById("record-value");
const editToolbarEl = document.getElementById("edit-toolbar");
const layoutToastEl = document.getElementById("layout-toast");
const modulePosDiagEl = document.getElementById("module-pos-diag");
const confirmDialogEl = document.getElementById("confirm-dialog");
const confirmTitleEl = document.getElementById("confirm-title");
const confirmBodyEl = document.getElementById("confirm-body");
const confirmOkBtn = document.getElementById("confirm-ok");
const confirmCancelBtn = document.getElementById("confirm-cancel");
const btnToggleBattle = document.getElementById("btn-toggle-battle");
const btnToggleTop5 = document.getElementById("btn-toggle-top5");
const btnToggleRecord = document.getElementById("btn-toggle-record");
const btnSaveLayout = document.getElementById("btn-save-layout");
const btnResetLayout = document.getElementById("btn-reset-layout");
const btnResetWins = document.getElementById("btn-reset-wins");
const btnResetPoints = document.getElementById("btn-reset-points");
const btnResetRecord = document.getElementById("btn-reset-record");
const btnResetAllStats = document.getElementById("btn-reset-all-stats");
const statsResetHintEl = document.getElementById("stats-reset-hint");
const reserveBatchInputEl = document.getElementById("reserve-batch-input");
const btnReserveBatchDec = document.getElementById("btn-reserve-batch-dec");
const btnReserveBatchInc = document.getElementById("btn-reserve-batch-inc");
const btnReserveBatchApply = document.getElementById("btn-reserve-batch-apply");
const reserveBatchCurrentEl = document.getElementById("reserve-batch-current");
const reserveBatchNewEl = document.getElementById("reserve-batch-new");
const reserveBatchNextEl = document.getElementById("reserve-batch-next");
const reserveBatchStatusEl = document.getElementById("reserve-batch-status");

/** @type {Map<string, HTMLElement>} Stable cards by country id */
const cardById = new Map();

let lastSequence = -1;
let previousById = new Map();
let reserveBatchCurrent = 10;
let reserveBatchApplying = false;
let eventQueue = [];
let eventBusy = false;
let flagsPreloaded = false;
/** @type {string[]} last rendered id order — preserve DOM order across HP-only updates */
let lastOrderIds = [];

const layoutState = {
  version: 3,
  battle: { ...DEFAULT_LAYOUT.battle },
  top5: { ...DEFAULT_LAYOUT.top5 },
  record: { ...DEFAULT_LAYOUT.record },
  px: { battle: { x: 0, y: 0 }, top5: { x: 0, y: 0 }, record: { x: 0, y: 0 } },
};
let statsResetAllowed = true;
let pendingConfirmAction = null;

function flagUrl(id) {
  return `${FLAG_BASE}/${id}.png`;
}

function flagCssUrl(id) {
  return `url("${flagUrl(id)}")`;
}

function preloadFlags() {
  if (flagsPreloaded) return;
  flagsPreloaded = true;
  for (const id of FLAG_IDS) {
    const img = new Image();
    img.decoding = "async";
    img.src = flagUrl(id);
  }
  debugLog(`preloaded ${FLAG_IDS.length} ${FLAG_FORMAT} flags`);
}

function phaseLabel(phase) {
  switch (phase) {
    case "BATTLE": return "БОЙ";
    case "WAITING_FOR_OPPONENT": return "ОЖИДАНИЕ";
    case "BREAK": return "ПЕРЕРЫВ";
    case "IDLE": return "ГОТОВО";
    default: return phase || "—";
  }
}

function timerLabel(phase) {
  switch (phase) {
    case "BATTLE": return "ДО КОНЦА";
    case "WAITING_FOR_OPPONENT": return "ОЖИДАНИЕ";
    case "BREAK": return "ПЕРЕРЫВ";
    case "IDLE": return "ВРЕМЯ";
    default: return "ВРЕМЯ";
  }
}

function countriesWord(n) {
  const abs = Math.abs(n) % 100;
  const last = abs % 10;
  if (abs > 10 && abs < 20) return "стран";
  if (last === 1) return "страна";
  if (last >= 2 && last <= 4) return "страны";
  return "стран";
}

/** Exported for tests via window when debug/edit. */
function winsWord(n) {
  const abs = Math.abs(n) % 100;
  const last = abs % 10;
  if (abs > 10 && abs < 20) return "побед";
  if (last === 1) return "победа";
  if (last >= 2 && last <= 4) return "победы";
  return "побед";
}

function formatWins(n) {
  const abs = Math.abs(n | 0);
  return `${abs} ${winsWord(abs)}`;
}

function stateClass(status) {
  switch (status) {
    case "PROTECTED": return "state-protected";
    case "VULNERABLE": return "state-vulnerable";
    case "RESCUE": return "state-rescue";
    case "ELIMINATED": return "state-eliminated";
    default: return "state-vulnerable";
  }
}

function statusBadge(country) {
  const protectedCore = country.coreProtected === true || country.status === "PROTECTED";
  const vulnerable = country.coreVulnerable === true || country.status === "VULNERABLE";
  if (country.eliminated || country.status === "ELIMINATED") {
    return { text: "ВЫБЫЛА", cls: "status status-eliminated" };
  }
  if (country.status === "RESCUE") {
    const sec = country.rescueRemaining ?? country.rescueSeconds ?? 0;
    return { text: `${sec}с`, cls: "status status-rescue" };
  }
  if (protectedCore) {
    return { text: "ЩИТ", cls: "status status-protected" };
  }
  if (vulnerable) {
    return { text: "БАЗА ОТКРЫТА", cls: "status status-vulnerable" };
  }
  return { text: "—", cls: "status status-vulnerable" };
}

function readCoreHp(country) {
  if (typeof country.coreHp === "number" && Number.isFinite(country.coreHp)) {
    return Math.round(country.coreHp);
  }
  return null;
}

function readCoreMaxHp(country) {
  if (typeof country.coreMaxHp === "number" && Number.isFinite(country.coreMaxHp) && country.coreMaxHp > 0) {
    return Math.round(country.coreMaxHp);
  }
  return null;
}

function hpFillClass(percent) {
  if (percent >= 60) return "core-hp-fill";
  if (percent >= 30) return "core-hp-fill hp-mid";
  return "core-hp-fill hp-low";
}

function densityClassForCount(count) {
  if (count <= 0) return "countries-0";
  if (count === 1) return "countries-1";
  if (count === 2) return "countries-2";
  if (count <= 4) return "countries-3-4";
  if (count <= 8) return "countries-5-8";
  if (count <= 12) return "countries-9-12";
  return "countries-13-20";
}

function cardSizeModeForCount(count) {
  if (count <= 2) return "LARGE";
  if (count <= 4) return "MEDIUM";
  if (count <= 12) return "COMPACT";
  return "ULTRA_COMPACT";
}

function gridColumnsForCount(count) {
  if (count <= 2) return 1;
  return 2;
}

/** Assign PNG background once per country id (or when id actually changes). */
function assignFlagAsset(flagEl, countryId) {
  if (flagEl.dataset.flagId === countryId) {
    return false;
  }
  flagEl.dataset.flagId = countryId;
  flagEl.style.backgroundImage = flagCssUrl(countryId);
  debugStats.flagAssetAssignments += 1;
  debugLog(`flag asset assigned id=${countryId} total=${debugStats.flagAssetAssignments}`);
  return true;
}

function createCard(country) {
  const el = document.createElement("div");
  el.className = `row ${stateClass(country.status)}`;
  el.dataset.id = country.id;

  const flag = document.createElement("div");
  flag.className = "country-flag";
  flag.setAttribute("role", "img");
  flag.setAttribute("aria-label", country.code || country.id);
  assignFlagAsset(flag, country.id);

  const code = document.createElement("div");
  code.className = "code";

  const live = document.createElement("div");
  live.className = "stat stat-live";
  const liveLabel = document.createElement("span");
  liveLabel.className = "stat-label";
  liveLabel.textContent = "БОЙЦЫ";
  const liveValue = document.createElement("span");
  liveValue.className = "stat-value";
  live.append(liveLabel, liveValue);

  const reserve = document.createElement("div");
  reserve.className = "stat stat-reserve";
  const reserveLabel = document.createElement("span");
  reserveLabel.className = "stat-label";
  reserveLabel.textContent = "РЕЗЕРВ";
  const reserveValue = document.createElement("span");
  reserveValue.className = "stat-value";
  reserve.append(reserveLabel, reserveValue);

  const status = document.createElement("div");

  const coreHp = document.createElement("div");
  coreHp.className = "core-hp";
  const coreLabel = document.createElement("div");
  coreLabel.className = "core-hp-label";
  coreLabel.textContent = "БАЗА";
  const coreValue = document.createElement("div");
  coreValue.className = "core-hp-value";
  const coreBar = document.createElement("div");
  coreBar.className = "core-hp-bar";
  const coreFill = document.createElement("div");
  coreFill.className = "core-hp-fill";
  coreBar.appendChild(coreFill);
  coreHp.append(coreLabel, coreValue, coreBar);

  el.append(flag, code, live, reserve, status, coreHp);
  el._refs = { flag, code, liveValue, reserveValue, status, coreValue, coreFill };
  debugStats.cardsCreated += 1;
  debugLog(`card created id=${country.id} total=${debugStats.cardsCreated}`);
  return el;
}

function updateCard(el, country) {
  const refs = el._refs;
  if (!refs) return;

  const nextClass = `row ${stateClass(country.status)}`;
  if (el.className !== nextClass) {
    el.className = nextClass;
  }

  assignFlagAsset(refs.flag, country.id);
  const label = country.code || country.id;
  if (refs.flag.getAttribute("aria-label") !== label) {
    refs.flag.setAttribute("aria-label", label);
  }

  const codeText = country.code || "";
  if (refs.code.textContent !== codeText) {
    refs.code.textContent = codeText;
  }

  const activeText = String(country.activeFighters ?? 0);
  const reserveText = String(country.reserve ?? 0);
  if (refs.liveValue.textContent !== activeText) {
    refs.liveValue.textContent = activeText;
  }
  if (refs.reserveValue.textContent !== reserveText) {
    refs.reserveValue.textContent = reserveText;
  }

  const badge = statusBadge(country);
  if (refs.status.className !== badge.cls) {
    refs.status.className = badge.cls;
  }
  if (refs.status.textContent !== badge.text) {
    refs.status.textContent = badge.text;
  }

  const hp = readCoreHp(country);
  const maxHp = readCoreMaxHp(country);
  let hpText;
  let percent = 0;
  if (hp == null || maxHp == null) {
    hpText = "HP: —";
    percent = 0;
  } else {
    hpText = `${hp} / ${maxHp}`;
    percent = Math.max(0, Math.min(100, Math.round((hp / Math.max(1, maxHp)) * 100)));
    if (typeof country.corePercent === "number" && Number.isFinite(country.corePercent)) {
      percent = Math.max(0, Math.min(100, Math.round(country.corePercent)));
    }
  }
  if (refs.coreValue.textContent !== hpText) {
    refs.coreValue.textContent = hpText;
  }
  const fillClass = hpFillClass(percent);
  if (refs.coreFill.className !== fillClass) {
    refs.coreFill.className = fillClass;
  }
  const width = `${percent}%`;
  if (refs.coreFill.style.width !== width) {
    refs.coreFill.style.width = width;
  }
}

function ensureCard(country) {
  let el = cardById.get(country.id);
  if (!el) {
    el = createCard(country);
    cardById.set(country.id, el);
  }
  updateCard(el, country);
  return el;
}

function showNote(text, isError) {
  noteEl.textContent = text;
  noteEl.classList.remove("hidden");
  noteEl.classList.toggle("error", !!isError);
}

function hideNote() {
  noteEl.classList.add("hidden");
  noteEl.classList.remove("error");
}

function enqueueEvent(event) {
  if (!event || !event.title) return;
  eventQueue.push(event);
  pumpEvents();
}

function pumpEvents() {
  if (eventBusy || eventQueue.length === 0) return;
  eventBusy = true;
  const event = eventQueue.shift();
  const type = event.type || "alert";
  eventBanner.className = `banner-${type}`;
  eventBanner.innerHTML = `
    <div class="banner-title">${event.title}</div>
    ${event.subtitle ? `<div class="banner-sub">${event.subtitle}</div>` : ""}
  `;
  eventBanner.classList.remove("hidden");
  setTimeout(() => {
    eventBanner.classList.add("hidden");
    eventBanner.className = "hidden";
    eventBusy = false;
    pumpEvents();
  }, EVENT_MS);
}

function detectEvents(countries) {
  const next = new Map();
  for (const c of countries) {
    next.set(c.id, c);
    const prev = previousById.get(c.id);
    if (!prev) continue;
    const name = (c.name || c.code || "").toUpperCase();
    if (!prev.eliminated && c.eliminated) {
      enqueueEvent({ title: name, subtitle: "ВЫБЫЛА", type: "elim" });
    } else if (prev.status !== "RESCUE" && c.status === "RESCUE") {
      const sec = c.rescueRemaining ?? c.rescueSeconds ?? 0;
      enqueueEvent({
        title: name,
        subtitle: `СПАСЕНИЕ · ${sec}с`,
        type: "rescue",
      });
    } else if (prev.activeFighters > 0 && c.activeFighters === 0 && !c.eliminated && c.status !== "RESCUE") {
      enqueueEvent({ title: name, subtitle: "БЕЗ ЗАЩИТНИКОВ", type: "alert" });
    } else {
      const deltaReserve = (c.reserve || 0) - (prev.reserve || 0);
      if (deltaReserve >= 10) {
        enqueueEvent({ title: name, subtitle: `ПОДКРЕПЛЕНИЕ +${deltaReserve}`, type: "reinforce" });
      }
    }
  }
  if (previousById.size > 0) {
    for (const [id, prev] of previousById) {
      if (!next.has(id) && !prev.eliminated) {
        enqueueEvent({
          title: (prev.name || prev.code || id || "").toUpperCase(),
          subtitle: "ВЫБЫЛА",
          type: "elim",
        });
      }
    }
    const alive = countries.filter(c => !c.eliminated);
    if (alive.length === 1) {
      const winner = alive[0];
      const prevAlive = [...previousById.values()].filter(c => !c.eliminated);
      if (prevAlive.length > 1) {
        enqueueEvent({
          title: (winner.name || winner.code || "").toUpperCase(),
          subtitle: "ПОБЕДА",
          type: "win",
        });
      }
    }
  }
  previousById = next;
}

function orderCountries(countries) {
  const byId = new Map(countries.map((c) => [c.id, c]));
  const hasJoin = countries.every((c) => typeof c.joinOrder === "number");
  if (hasJoin) {
    return countries.slice().sort((a, b) => (a.joinOrder ?? 999) - (b.joinOrder ?? 999));
  }

  const sameSet =
    lastOrderIds.length === countries.length
    && lastOrderIds.every((id) => byId.has(id));
  if (sameSet) {
    return lastOrderIds.map((id) => byId.get(id));
  }

  return countries.slice().sort((a, b) => (a.baseSlot ?? 999) - (b.baseSlot ?? 999));
}

function fillGrid(list) {
  const desired = list.map((country) => ensureCard(country));

  for (let i = 0; i < desired.length; i++) {
    const want = desired[i];
    const current = gridEl.children[i];
    if (current !== want) {
      gridEl.insertBefore(want, current || null);
    }
  }

  while (gridEl.children.length > desired.length) {
    gridEl.removeChild(gridEl.lastChild);
  }

  const keep = new Set(list.map((c) => c.id));
  for (const id of [...cardById.keys()]) {
    if (!keep.has(id)) {
      cardById.delete(id);
    }
  }
}

function clearGridKeepCards() {
  while (gridEl.firstChild) gridEl.removeChild(gridEl.firstChild);
}

function applyDensityClass(count) {
  for (const cls of DENSITY_CLASSES) {
    document.body.classList.remove(cls);
  }
  document.body.classList.add(densityClassForCount(count));
}

function detectOverflow() {
  if (!gridEl || !battleModuleEl) return false;
  const box = battleModuleEl.getBoundingClientRect();
  const cards = gridEl.querySelectorAll(".row");
  if (cards.length === 0) return false;
  let overflow = false;
  for (const card of cards) {
    const cardBox = card.getBoundingClientRect();
    if (cardBox.bottom > box.bottom + 0.5 || cardBox.top < box.top - 0.5) {
      overflow = true;
      break;
    }
  }
  const scrollOverflow = gridEl.scrollHeight > gridEl.clientHeight + 1
    || gridEl.scrollWidth > gridEl.clientWidth + 1;
  return overflow || scrollOverflow;
}

function medalForRank(rank) {
  if (rank === 1) return "🥇";
  if (rank === 2) return "🥈";
  if (rank === 3) return "🥉";
  return "";
}

function renderTopFive(data) {
  if (!top5ListEl || !top5EmptyEl) return;
  const list = Array.isArray(data.topCountries) ? data.topCountries : [];
  const withWins = list.filter((c) => (c.roundWins | 0) > 0).slice(0, 5);

  while (top5ListEl.firstChild) {
    top5ListEl.removeChild(top5ListEl.firstChild);
  }

  if (withWins.length === 0) {
    top5EmptyEl.classList.remove("hidden");
    return;
  }

  top5EmptyEl.classList.add("hidden");
  for (const entry of withWins) {
    const rank = entry.rank | 0;
    const winsCount = entry.roundWins | 0;
    const row = document.createElement("div");
    row.className = `top5-row rank-${Math.min(5, Math.max(1, rank))}`;

    const place = document.createElement("div");
    place.className = "top5-place";
    const medal = medalForRank(rank);
    place.innerHTML = medal
      ? `${rank}<span class="top5-medal">${medal}</span>`
      : String(rank);

    const flag = document.createElement("div");
    flag.className = "top5-flag";
    const id = entry.countryId || entry.id || "";
    flag.style.backgroundImage = flagCssUrl(id);
    flag.setAttribute("role", "img");
    flag.setAttribute("aria-label", entry.displayName || entry.code || id);

    const name = document.createElement("div");
    name.className = "top5-name";
    name.textContent = entry.displayName || entry.code || id;

    const wins = document.createElement("span");
    wins.className = "top5-wins";
    const winsNumber = document.createElement("span");
    winsNumber.className = "top5-wins-number";
    winsNumber.textContent = String(winsCount);
    const winsWordEl = document.createElement("span");
    winsWordEl.className = "top5-wins-word";
    winsWordEl.textContent = winsWord(winsCount);
    wins.append(winsNumber, winsWordEl);

    row.append(place, flag, name, wins);
    top5ListEl.appendChild(row);
  }
}

function renderRecord(data) {
  if (!recordFlagEl || !recordValueEl) return;
  const record = data && data.fighterRoundRecord ? data.fighterRoundRecord : null;
  const count = record && typeof record.fighterCount === "number" ? record.fighterCount : 0;
  const id = record && record.countryId ? String(record.countryId) : "";
  recordValueEl.textContent = String(Math.max(0, count | 0));
  if (id && count > 0) {
    recordFlagEl.classList.remove("record-flag-empty");
    recordFlagEl.style.backgroundImage = flagCssUrl(id);
    recordFlagEl.setAttribute("aria-label", record.displayName || id);
  } else {
    recordFlagEl.classList.add("record-flag-empty");
    recordFlagEl.style.backgroundImage = "";
    recordFlagEl.setAttribute("aria-label", "");
  }
}

function updateStatsResetAvailability(data) {
  const allowed = data && data.statsResetAllowed === true
    ? true
    : (data && data.statsResetAllowed === false ? false : statsResetAllowed);
  statsResetAllowed = allowed;
  const buttons = [btnResetWins, btnResetPoints, btnResetRecord, btnResetAllStats];
  for (const btn of buttons) {
    if (!btn) continue;
    btn.disabled = !allowed;
    btn.classList.toggle("is-disabled", !allowed);
  }
  if (statsResetHintEl) {
    statsResetHintEl.classList.toggle("hidden", allowed);
  }
}

function clamp01(value) {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(1, value));
}

function workspaceSize() {
  if (!workspaceEl) {
    return { w: Math.max(window.innerWidth, CANVAS_W), h: Math.max(window.innerHeight, CANVAS_H) };
  }
  const w = Math.max(workspaceEl.offsetWidth || 0, workspaceEl.clientWidth || 0, CANVAS_W);
  const h = Math.max(workspaceEl.offsetHeight || 0, workspaceEl.clientHeight || 0, CANVAS_H);
  return { w, h };
}

function moduleSize(el) {
  if (!el) return { w: 1, h: 1 };
  return {
    w: Math.max(1, el.offsetWidth || el.getBoundingClientRect().width || 1),
    h: Math.max(1, el.offsetHeight || el.getBoundingClientRect().height || 1),
  };
}

function clampFullyInside(el, x, y) {
  const ws = workspaceSize();
  const size = moduleSize(el);
  const maxX = Math.max(0, ws.w - size.w);
  const maxY = Math.max(0, ws.h - size.h);
  return {
    x: Math.round(Math.max(0, Math.min(maxX, x))),
    y: Math.round(Math.max(0, Math.min(maxY, y))),
  };
}

function pxFromRatio(el, xRatio, yRatio) {
  const ws = workspaceSize();
  const size = moduleSize(el);
  const spanX = Math.max(1, ws.w - size.w);
  const spanY = Math.max(1, ws.h - size.h);
  return clampFullyInside(el, clamp01(xRatio) * spanX, clamp01(yRatio) * spanY);
}

function ratioFromPx(el, x, y) {
  const ws = workspaceSize();
  const size = moduleSize(el);
  const spanX = Math.max(1, ws.w - size.w);
  const spanY = Math.max(1, ws.h - size.h);
  const clamped = clampFullyInside(el, x, y);
  return {
    xRatio: clamp01(clamped.x / spanX),
    yRatio: clamp01(clamped.y / spanY),
    x: clamped.x,
    y: clamped.y,
  };
}

function applyModuleFromRatio(key) {
  const el = key === "battle" ? battleModuleEl : (key === "top5" ? top5ModuleEl : recordModuleEl);
  if (!el) return;
  const mod = layoutState[key];
  const px = pxFromRatio(el, mod.xRatio, mod.yRatio);
  el.style.left = `${px.x}px`;
  el.style.top = `${px.y}px`;
  const scale = typeof mod.scale === "number" && Number.isFinite(mod.scale) ? mod.scale : 1;
  el.style.transform = scale === 1 ? "" : `scale(${scale})`;
  layoutState.px[key] = px;
  return px;
}

function applyAllModulePositions() {
  applyModuleFromRatio("battle");
  applyModuleFromRatio("top5");
  applyModuleFromRatio("record");
  applyVisibility();
  updateModulePosDiag();
}

function applyVisibility() {
  if (battleModuleEl) {
    battleModuleEl.classList.toggle("module-user-hidden", !layoutState.battle.visible);
  }
  if (top5ModuleEl) {
    top5ModuleEl.classList.toggle("module-user-hidden", !layoutState.top5.visible);
  }
  if (recordModuleEl) {
    recordModuleEl.classList.toggle("module-user-hidden", !layoutState.record.visible);
  }
  if (btnToggleBattle) {
    btnToggleBattle.textContent = layoutState.battle.visible ? "БОЙ: ВКЛ" : "БОЙ: ВЫКЛ";
    btnToggleBattle.classList.toggle("is-off", !layoutState.battle.visible);
  }
  if (btnToggleTop5) {
    btnToggleTop5.textContent = layoutState.top5.visible ? "ТОП-5: ВКЛ" : "ТОП-5: ВЫКЛ";
    btnToggleTop5.classList.toggle("is-off", !layoutState.top5.visible);
  }
  if (btnToggleRecord) {
    btnToggleRecord.textContent = layoutState.record.visible ? "РЕКОРД: ВКЛ" : "РЕКОРД: ВЫКЛ";
    btnToggleRecord.classList.toggle("is-off", !layoutState.record.visible);
  }
}

function updateModulePosDiag() {
  if (!preview || !modulePosDiagEl) return;
  const ws = workspaceSize();
  const b = moduleSize(battleModuleEl);
  const t = moduleSize(top5ModuleEl);
  const r = moduleSize(recordModuleEl);
  modulePosDiagEl.textContent =
    `ws=${ws.w}x${ws.h}`
    + ` · battle px=${layoutState.px.battle.x},${layoutState.px.battle.y}`
    + ` r=${layoutState.battle.xRatio.toFixed(3)},${layoutState.battle.yRatio.toFixed(3)}`
    + ` size=${b.w}x${b.h}`
    + ` · top5 px=${layoutState.px.top5.x},${layoutState.px.top5.y}`
    + ` r=${layoutState.top5.xRatio.toFixed(3)},${layoutState.top5.yRatio.toFixed(3)}`
    + ` size=${t.w}x${t.h}`
    + ` · record px=${layoutState.px.record.x},${layoutState.px.record.y}`
    + ` r=${layoutState.record.xRatio.toFixed(3)},${layoutState.record.yRatio.toFixed(3)}`
    + ` size=${r.w}x${r.h}`
    + ` · vis=${layoutState.battle.visible}/${layoutState.top5.visible}/${layoutState.record.visible}`;
  modulePosDiagEl.classList.remove("hidden");
}

function showLayoutToast(text, isError) {
  if (!layoutToastEl) return;
  layoutToastEl.textContent = text;
  layoutToastEl.classList.toggle("error", !!isError);
  layoutToastEl.classList.remove("hidden");
  clearTimeout(showLayoutToast._timer);
  showLayoutToast._timer = setTimeout(() => {
    layoutToastEl.classList.add("hidden");
  }, 1600);
}

function readStoredJson(key) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch (_e) {
    return null;
  }
}

function readStoredVisible(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null || raw === undefined) return fallback;
    return raw !== "0" && raw !== "false";
  } catch (_e) {
    return fallback;
  }
}

function writeLocalCache(layout) {
  try {
    localStorage.setItem("arenaOverlay.layout.serverCache", JSON.stringify(layout));
  } catch (_e) {
    /* ignore */
  }
}

function applyServerLayout(data) {
  const battle = data && data.battle ? data.battle : DEFAULT_LAYOUT.battle;
  const top5 = data && data.top5 ? data.top5 : DEFAULT_LAYOUT.top5;
  const record = data && data.record ? data.record : DEFAULT_LAYOUT.record;
  layoutState.version = 3;
  layoutState.battle = {
    xRatio: clamp01(typeof battle.xRatio === "number" ? battle.xRatio : DEFAULT_LAYOUT.battle.xRatio),
    yRatio: clamp01(typeof battle.yRatio === "number" ? battle.yRatio : DEFAULT_LAYOUT.battle.yRatio),
    visible: battle.visible !== false,
    scale: typeof battle.scale === "number" ? battle.scale : 1,
  };
  layoutState.top5 = {
    xRatio: clamp01(typeof top5.xRatio === "number" ? top5.xRatio : DEFAULT_LAYOUT.top5.xRatio),
    yRatio: clamp01(typeof top5.yRatio === "number" ? top5.yRatio : DEFAULT_LAYOUT.top5.yRatio),
    visible: top5.visible !== false,
    scale: typeof top5.scale === "number" ? top5.scale : 1,
  };
  layoutState.record = {
    xRatio: clamp01(typeof record.xRatio === "number" ? record.xRatio : DEFAULT_LAYOUT.record.xRatio),
    yRatio: clamp01(typeof record.yRatio === "number" ? record.yRatio : DEFAULT_LAYOUT.record.yRatio),
    visible: record.visible !== false,
    scale: typeof record.scale === "number" ? record.scale : 1,
  };
  applyAllModulePositions();
  writeLocalCache({
    version: 3,
    battle: layoutState.battle,
    top5: layoutState.top5,
    record: layoutState.record,
  });
}

function buildLayoutPayload(extra) {
  const payload = {
    version: 3,
    battle: {
      xRatio: layoutState.battle.xRatio,
      yRatio: layoutState.battle.yRatio,
      visible: layoutState.battle.visible,
      scale: layoutState.battle.scale ?? 1,
    },
    top5: {
      xRatio: layoutState.top5.xRatio,
      yRatio: layoutState.top5.yRatio,
      visible: layoutState.top5.visible,
      scale: layoutState.top5.scale ?? 1,
    },
    record: {
      xRatio: layoutState.record.xRatio,
      yRatio: layoutState.record.yRatio,
      visible: layoutState.record.visible,
      scale: layoutState.record.scale ?? 1,
    },
  };
  if (extra && typeof extra === "object") {
    Object.assign(payload, extra);
  }
  return payload;
}

async function postLayout(extra) {
  if (btnSaveLayout) {
    btnSaveLayout.textContent = "СОХРАНЕНИЕ…";
  }
  try {
    const response = await fetch(LAYOUT_API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      cache: "no-store",
      body: JSON.stringify(buildLayoutPayload(extra)),
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const data = await response.json();
    applyServerLayout(data);
    if (btnSaveLayout) {
      btnSaveLayout.textContent = "СОХРАНЕНО";
      setTimeout(() => {
        if (btnSaveLayout) btnSaveLayout.textContent = "СОХРАНИТЬ";
      }, 1000);
    }
    return true;
  } catch (_err) {
    if (btnSaveLayout) {
      btnSaveLayout.textContent = "ОШИБКА СОХРАНЕНИЯ";
      setTimeout(() => {
        if (btnSaveLayout) btnSaveLayout.textContent = "СОХРАНИТЬ";
      }, 1600);
    }
    showLayoutToast("ОШИБКА СОХРАНЕНИЯ", true);
    return false;
  }
}

function legacyPxToRatio(el, x, y) {
  const converted = ratioFromPx(el, x, y);
  return { xRatio: converted.xRatio, yRatio: converted.yRatio };
}

function tryBuildMigrationFromLocalStorage() {
  try {
    if (localStorage.getItem(STORAGE_MIGRATED) === "1") {
      return null;
    }
  } catch (_e) {
    return null;
  }
  const battlePos = readStoredJson(STORAGE_BATTLE_POS);
  const top5Pos = readStoredJson(STORAGE_TOP5_POS);
  if (!battlePos && !top5Pos) {
    return null;
  }
  const battleVis = readStoredVisible(STORAGE_BATTLE_VIS, true);
  const top5Vis = readStoredVisible(STORAGE_TOP5_VIS, true);
  const battleRatio = battlePos && typeof battlePos.x === "number"
    ? legacyPxToRatio(battleModuleEl, battlePos.x, battlePos.y)
    : { xRatio: DEFAULT_LAYOUT.battle.xRatio, yRatio: DEFAULT_LAYOUT.battle.yRatio };
  const top5Ratio = top5Pos && typeof top5Pos.x === "number"
    ? legacyPxToRatio(top5ModuleEl, top5Pos.x, top5Pos.y)
    : { xRatio: DEFAULT_LAYOUT.top5.xRatio, yRatio: DEFAULT_LAYOUT.top5.yRatio };
  return {
    version: 2,
    battle: { ...battleRatio, visible: battleVis },
    top5: { ...top5Ratio, visible: top5Vis },
    migratedFromLocalStorage: true,
  };
}

async function loadLayoutFromServer() {
  applyServerLayout(DEFAULT_LAYOUT);
  try {
    const response = await fetch(LAYOUT_API, { cache: "no-store" });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const data = await response.json();
    const fileExists = data.configFileExists === true;
    const alreadyMigrated = data.legacyLocalStorageMigrated === true;
    if (fileExists) {
      applyServerLayout(data);
      return;
    }
    applyServerLayout(data);
    if (!alreadyMigrated) {
      const migration = tryBuildMigrationFromLocalStorage();
      if (migration) {
        const ok = await postLayout(migration);
        if (ok) {
          try { localStorage.setItem(STORAGE_MIGRATED, "1"); } catch (_e) { /* ignore */ }
        }
        return;
      }
      // First boot without legacy data — persist defaults so all browsers share them.
      await postLayout();
    }
    return;
  } catch (_err) {
    debugLog("layout GET failed — using defaults / migration");
  }

  const migration = tryBuildMigrationFromLocalStorage();
  if (migration) {
    applyServerLayout(migration);
    const ok = await postLayout(migration);
    if (ok) {
      try { localStorage.setItem(STORAGE_MIGRATED, "1"); } catch (_e) { /* ignore */ }
    }
  }
}

async function resetLayoutPositions() {
  if (btnResetLayout) {
    btnResetLayout.textContent = "СБРОС…";
  }
  try {
    const response = await fetch(LAYOUT_RESET_API, {
      method: "POST",
      cache: "no-store",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const data = await response.json();
    applyServerLayout(data);
    showLayoutToast("ПОЗИЦИИ СБРОШЕНЫ", false);
  } catch (_err) {
    showLayoutToast("ОШИБКА СОХРАНЕНИЯ", true);
  } finally {
    if (btnResetLayout) {
      btnResetLayout.textContent = "СБРОСИТЬ ПОЗИЦИИ";
    }
  }
}

function setupResizeReposition() {
  let timer = null;
  const recompute = () => {
    applyAllModulePositions();
  };
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(recompute, 80);
  };
  window.addEventListener("resize", schedule);
  if (typeof ResizeObserver !== "undefined" && workspaceEl) {
    const ro = new ResizeObserver(schedule);
    ro.observe(workspaceEl);
  }
}

function setupDrag() {
  if (editToolbarEl && editMode) {
    editToolbarEl.classList.remove("hidden");
  }
  if (!editMode) return;

  const modules = {
    battle: battleModuleEl,
    top5: top5ModuleEl,
    record: recordModuleEl,
  };

  /** @type {{ key: string, el: HTMLElement, startX: number, startY: number, origX: number, origY: number, pointerId: number } | null} */
  let drag = null;
  let autoScrollTimer = null;
  let lastPointer = { x: 0, y: 0 };

  function selectModule(key) {
    for (const [k, el] of Object.entries(modules)) {
      if (!el) continue;
      el.classList.toggle("selected", k === key);
    }
  }

  function stopAutoScroll() {
    if (autoScrollTimer != null) {
      clearTimeout(autoScrollTimer);
      autoScrollTimer = null;
    }
  }

  function scheduleAutoScroll() {
    stopAutoScroll();
    autoScrollTimer = setTimeout(() => {
      autoScrollTimer = null;
      if (!drag) return;
      maybeAutoScroll();
      scheduleAutoScroll();
    }, 32);
  }

  function applyDragPosition(clientX, clientY) {
    if (!drag || !workspaceEl) return;
    const wsRect = workspaceEl.getBoundingClientRect();
    const dx = clientX - drag.startX;
    const dy = clientY - drag.startY;
    // Positions are workspace-local left/top; pointer deltas are viewport px (1:1 with layout px).
    const next = clampFullyInside(drag.el, drag.origX + dx, drag.origY + dy);
    drag.el.style.left = `${next.x}px`;
    drag.el.style.top = `${next.y}px`;
    layoutState.px[drag.key] = next;
    updateModulePosDiag();
  }

  function maybeAutoScroll() {
    if (!drag || !editMode) return;
    const y = lastPointer.y;
    const vh = window.innerHeight || document.documentElement.clientHeight || 0;
    let delta = 0;
    if (y < AUTO_SCROLL_EDGE_PX) {
      delta = -AUTO_SCROLL_STEP_PX;
    } else if (y > vh - AUTO_SCROLL_EDGE_PX) {
      delta = AUTO_SCROLL_STEP_PX;
    }
    if (delta !== 0) {
      window.scrollBy(0, delta);
      applyDragPosition(lastPointer.x, lastPointer.y);
    }
  }

  function onPointerMove(ev) {
    if (!drag) return;
    lastPointer = { x: ev.clientX, y: ev.clientY };
    applyDragPosition(ev.clientX, ev.clientY);
  }

  async function onPointerUp(ev) {
    if (!drag) return;
    const active = drag;
    drag = null;
    stopAutoScroll();
    document.body.classList.remove("dragging-active");
    active.el.classList.remove("dragging");
    try {
      active.el.releasePointerCapture(ev.pointerId);
    } catch (_e) {
      /* ignore */
    }
    const left = parseInt(active.el.style.left, 10) || 0;
    const top = parseInt(active.el.style.top, 10) || 0;
    const converted = ratioFromPx(active.el, left, top);
    active.el.style.left = `${converted.x}px`;
    active.el.style.top = `${converted.y}px`;
    layoutState.px[active.key] = { x: converted.x, y: converted.y };
    layoutState[active.key].xRatio = converted.xRatio;
    layoutState[active.key].yRatio = converted.yRatio;
    window.removeEventListener("pointermove", onPointerMove);
    window.removeEventListener("pointerup", onPointerUp);
    window.removeEventListener("pointercancel", onPointerUp);
    const ok = await postLayout();
    if (ok) {
      showLayoutToast("ПОЗИЦИЯ СОХРАНЕНА", false);
    }
  }

  for (const handle of document.querySelectorAll(".module-drag-handle")) {
    handle.addEventListener("pointerdown", (ev) => {
      if (ev.button !== undefined && ev.button !== 0) return;
      const key = handle.getAttribute("data-drag-for");
      const el = modules[key];
      if (!el || !key) return;
      ev.preventDefault();
      selectModule(key);
      const rectLeft = parseInt(el.style.left, 10);
      const rectTop = parseInt(el.style.top, 10);
      const origX = Number.isFinite(rectLeft) ? rectLeft : layoutState.px[key].x;
      const origY = Number.isFinite(rectTop) ? rectTop : layoutState.px[key].y;
      drag = {
        key,
        el,
        startX: ev.clientX,
        startY: ev.clientY,
        origX,
        origY,
        pointerId: ev.pointerId,
      };
      lastPointer = { x: ev.clientX, y: ev.clientY };
      document.body.classList.add("dragging-active");
      el.classList.add("dragging");
      try {
        handle.setPointerCapture(ev.pointerId);
      } catch (_e) {
        el.setPointerCapture(ev.pointerId);
      }
      stopAutoScroll();
      scheduleAutoScroll();
      window.addEventListener("pointermove", onPointerMove);
      window.addEventListener("pointerup", onPointerUp);
      window.addEventListener("pointercancel", onPointerUp);
    });
  }

  if (btnToggleBattle) {
    btnToggleBattle.addEventListener("click", async () => {
      layoutState.battle.visible = !layoutState.battle.visible;
      applyVisibility();
      const ok = await postLayout();
      if (ok) showLayoutToast("ПОЗИЦИЯ СОХРАНЕНА", false);
    });
  }
  if (btnToggleTop5) {
    btnToggleTop5.addEventListener("click", async () => {
      layoutState.top5.visible = !layoutState.top5.visible;
      applyVisibility();
      const ok = await postLayout();
      if (ok) showLayoutToast("ПОЗИЦИЯ СОХРАНЕНА", false);
    });
  }
  if (btnToggleRecord) {
    btnToggleRecord.addEventListener("click", async () => {
      layoutState.record.visible = !layoutState.record.visible;
      applyVisibility();
      const ok = await postLayout();
      if (ok) showLayoutToast("ПОЗИЦИЯ СОХРАНЕНА", false);
    });
  }
  if (btnSaveLayout) {
    btnSaveLayout.addEventListener("click", async () => {
      const ok = await postLayout();
      if (ok) showLayoutToast("ПОЗИЦИЯ СОХРАНЕНА", false);
    });
  }
  if (btnResetLayout) {
    btnResetLayout.addEventListener("click", () => {
      resetLayoutPositions();
    });
  }

  setupStatsResetButtons();
  setupReserveBatchControls();
}

function openConfirmDialog(title, body, danger, onConfirm) {
  if (!confirmDialogEl) {
    onConfirm();
    return;
  }
  pendingConfirmAction = onConfirm;
  if (confirmTitleEl) confirmTitleEl.textContent = title;
  if (confirmBodyEl) confirmBodyEl.textContent = body;
  const card = confirmDialogEl.querySelector(".confirm-card");
  if (card) card.classList.toggle("is-danger", !!danger);
  confirmDialogEl.classList.remove("hidden");
}

function closeConfirmDialog() {
  pendingConfirmAction = null;
  if (confirmDialogEl) confirmDialogEl.classList.add("hidden");
}

async function postStatsReset(url) {
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      cache: "no-store",
      body: JSON.stringify({ confirm: true }),
    });
    let data = null;
    try {
      data = await response.json();
    } catch (_e) {
      data = null;
    }
    if (!response.ok || !data || data.success !== true) {
      const msg = (data && data.message) ? data.message : `HTTP ${response.status}`;
      showLayoutToast(msg, true);
      return false;
    }
    showLayoutToast(data.message || "СБРОШЕНО", false);
    return true;
  } catch (_err) {
    showLayoutToast("ОШИБКА СБРОСА", true);
    return false;
  }
}

function setupStatsResetButtons() {
  if (confirmOkBtn) {
    confirmOkBtn.addEventListener("click", async () => {
      const action = pendingConfirmAction;
      closeConfirmDialog();
      if (typeof action === "function") {
        await action();
      }
    });
  }
  if (confirmCancelBtn) {
    confirmCancelBtn.addEventListener("click", () => closeConfirmDialog());
  }

  function bindReset(btn, title, body, danger, url) {
    if (!btn) return;
    btn.addEventListener("click", () => {
      if (!statsResetAllowed) {
        showLayoutToast("СБРОС ДОСТУПЕН В ПЕРЕРЫВЕ", true);
        return;
      }
      openConfirmDialog(title, body, danger, () => postStatsReset(url));
    });
  }

  bindReset(
    btnResetWins,
    "СБРОСИТЬ ТОП-5 ПОБЕД?",
    "Будут обнулены только победы раундов (roundWins).",
    false,
    STATS_RESET.wins,
  );
  bindReset(
    btnResetPoints,
    "СБРОСИТЬ ОЧКИ СТРАН?",
    "Будут обнулены только очки стран. Победы и рекорд бойцов сохранятся.",
    false,
    STATS_RESET.points,
  );
  bindReset(
    btnResetRecord,
    "СБРОСИТЬ РЕКОРД БОЙЦОВ?",
    "Постоянный рекорд бойцов за раунд будет обнулён.",
    false,
    STATS_RESET.record,
  );
  bindReset(
    btnResetAllStats,
    "СБРОСИТЬ ВСЮ СТАТИСТИКУ?",
    "БУДУТ УДАЛЕНЫ:\n- победы;\n- очки;\n- рекорд бойцов.",
    true,
    STATS_RESET.all,
  );
}

function setReserveBatchStatus(text, kind) {
  if (!reserveBatchStatusEl) return;
  reserveBatchStatusEl.textContent = text || "";
  reserveBatchStatusEl.classList.toggle("is-error", kind === "error");
  reserveBatchStatusEl.classList.toggle("is-pending", kind === "pending");
}

function updateReserveBatchLabels(draft) {
  const current = Math.max(RESERVE_BATCH_MIN, Math.min(RESERVE_BATCH_MAX, reserveBatchCurrent | 0));
  if (reserveBatchCurrentEl) reserveBatchCurrentEl.textContent = String(current);
  const newVal = Number.isFinite(draft) ? draft : readReserveBatchDraft();
  if (reserveBatchNewEl) {
    reserveBatchNewEl.textContent = Number.isFinite(newVal) ? String(newVal) : "—";
  }
  if (reserveBatchNextEl) {
    const next = Number.isFinite(newVal) ? newVal : current;
    reserveBatchNextEl.textContent =
      `СЛЕДУЮЩАЯ ВОЛНА: МАКСИМУМ ${next} БОЙЦОВ НА СТРАНУ`;
  }
}

function readReserveBatchDraft() {
  if (!reserveBatchInputEl) return NaN;
  const raw = String(reserveBatchInputEl.value || "").trim();
  if (!raw) return NaN;
  if (!/^-?\d+$/.test(raw)) return NaN;
  const n = Number(raw);
  if (!Number.isInteger(n) || Number.isNaN(n)) return NaN;
  return n;
}

function setReserveBatchDraft(value, syncInput) {
  const n = Math.max(RESERVE_BATCH_MIN, Math.min(RESERVE_BATCH_MAX, value | 0));
  if (syncInput && reserveBatchInputEl) {
    reserveBatchInputEl.value = String(n);
  }
  updateReserveBatchLabels(n);
  return n;
}

function applyServerReserveBatch(value, forceInput) {
  const n = Math.max(RESERVE_BATCH_MIN, Math.min(RESERVE_BATCH_MAX, value | 0));
  reserveBatchCurrent = n;
  if (forceInput || !reserveBatchInputEl || document.activeElement !== reserveBatchInputEl) {
    if (reserveBatchInputEl) reserveBatchInputEl.value = String(n);
  }
  updateReserveBatchLabels(n);
}

function syncReserveBatchFromSnapshot(data) {
  if (!editMode) return;
  const runtime = data && data.runtimeSettings ? data.runtimeSettings : null;
  if (!runtime || typeof runtime.reserveReleaseBatch !== "number") return;
  if (reserveBatchApplying) return;
  applyServerReserveBatch(runtime.reserveReleaseBatch, false);
}

async function loadReserveSettingsFromServer() {
  if (!editMode) return;
  try {
    const response = await fetch(RESERVE_SETTINGS_API, { cache: "no-store" });
    const data = await response.json();
    if (!response.ok || !data || data.success !== true) return;
    if (typeof data.reserveReleaseBatch === "number") {
      applyServerReserveBatch(data.reserveReleaseBatch, true);
    }
  } catch (_err) {
    // Keep defaults until snapshot/poll succeeds.
  }
}

async function postReserveBatchApply() {
  if (!editMode || reserveBatchApplying) return;
  const draft = readReserveBatchDraft();
  if (!Number.isInteger(draft) || Number.isNaN(draft)) {
    setReserveBatchStatus("ОШИБКА: Введите целое число от 1 до 100", "error");
    return;
  }
  if (draft < RESERVE_BATCH_MIN || draft > RESERVE_BATCH_MAX) {
    setReserveBatchStatus("ОШИБКА: Допустимое значение: от 1 до 100", "error");
    return;
  }
  reserveBatchApplying = true;
  if (btnReserveBatchApply) btnReserveBatchApply.disabled = true;
  setReserveBatchStatus("ПРИМЕНЕНИЕ…", "pending");
  try {
    const response = await fetch(RESERVE_SETTINGS_API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      cache: "no-store",
      body: JSON.stringify({ reserveReleaseBatch: draft }),
    });
    let data = null;
    try {
      data = await response.json();
    } catch (_e) {
      data = null;
    }
    if (!response.ok || !data || data.success !== true) {
      const msg = (data && data.message) ? data.message : `HTTP ${response.status}`;
      setReserveBatchStatus(`ОШИБКА: ${msg}`, "error");
      return;
    }
    const applied = typeof data.reserveReleaseBatch === "number" ? data.reserveReleaseBatch : draft;
    applyServerReserveBatch(applied, true);
    setReserveBatchStatus(`ПРИМЕНЕНО: ${applied} БОЙЦОВ ЗА ВОЛНУ`, "ok");
  } catch (err) {
    const msg = err && err.message ? err.message : "сетевая ошибка";
    setReserveBatchStatus(`ОШИБКА: ${msg}`, "error");
  } finally {
    reserveBatchApplying = false;
    if (btnReserveBatchApply) btnReserveBatchApply.disabled = false;
  }
}

function setupReserveBatchControls() {
  if (!editMode) return;
  loadReserveSettingsFromServer();

  if (btnReserveBatchDec) {
    btnReserveBatchDec.addEventListener("click", () => {
      const cur = readReserveBatchDraft();
      const base = Number.isInteger(cur) ? cur : reserveBatchCurrent;
      setReserveBatchDraft(base - 1, true);
    });
  }
  if (btnReserveBatchInc) {
    btnReserveBatchInc.addEventListener("click", () => {
      const cur = readReserveBatchDraft();
      const base = Number.isInteger(cur) ? cur : reserveBatchCurrent;
      setReserveBatchDraft(base + 1, true);
    });
  }
  if (reserveBatchInputEl) {
    reserveBatchInputEl.addEventListener("input", () => {
      updateReserveBatchLabels(readReserveBatchDraft());
    });
  }
  document.querySelectorAll(".reserve-preset-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      const n = Number(btn.getAttribute("data-batch"));
      if (!Number.isInteger(n)) return;
      setReserveBatchDraft(n, true);
    });
  });
  if (btnReserveBatchApply) {
    btnReserveBatchApply.addEventListener("click", () => {
      postReserveBatchApply();
    });
  }
  updateReserveBatchLabels(reserveBatchCurrent);
}

function updatePreviewDiag(data, countries, overflow) {
  if (!preview || !previewDiagEl) return;
  const count = countries.length;
  const mode = data.overlayCardSizeMode || cardSizeModeForCount(count);
  const cols = data.overlayGridColumns ?? gridColumnsForCount(count);
  const codes = data.overlayDisplayedCountries
    || countries.map((c) => c.code || c.id).join(",");
  const topCodes = Array.isArray(data.topCountries)
    ? data.topCountries.map((c) => `${c.code || c.countryId}:${c.roundWins || 0}`).join(",")
    : "";
  previewDiagEl.textContent =
    `src=${data.overlayParticipantSource || "CURRENT_ROUND_PARTICIPANTS"}`
    + ` · n=${count}`
    + ` · elim=${data.overlayEliminatedCountries || "—"}`
    + ` · cols=${cols}`
    + ` · mode=${mode}`
    + ` · holder=${data.overlayWaitingHolderIncluded === true}`
    + ` · elimCards=${data.overlayEliminatedCardsVisible ?? 0}`
    + ` · overflow=${overflow}`
    + ` · top5=${topCodes || "—"}`
    + ` · ${codes || "—"}`;
  previewDiagEl.classList.remove("hidden");
  updateModulePosDiag();
}

function render(data) {
  debugStats.snapshotsUpdated += 1;
  if (debug && debugStats.snapshotsUpdated % 20 === 0) {
    debugLog("snapshot heartbeat");
  }
  if (!phaseEl || !timerEl || !timerLabelEl || !remainingEl || !gridEl) {
    debugLog("missing hud nodes — HTML/JS mismatch (hard-refresh browser window)");
    return;
  }

  const phase = data.phase || "IDLE";
  phaseEl.textContent = phaseLabel(phase);
  timerLabelEl.textContent = timerLabel(phase);

  const sec = Math.max(0, data.remainingSeconds || 0);
  const mm = String(Math.floor(sec / 60)).padStart(2, "0");
  const ss = String(sec % 60).padStart(2, "0");
  timerEl.textContent = `${mm}:${ss}`;

  const raw = Array.isArray(data.countries)
    ? data.countries.filter((c) => !(c && (c.eliminated === true || c.status === "ELIMINATED")))
    : [];
  const countries = orderCountries(raw);

  const count = typeof data.activeCountryCount === "number"
    ? data.activeCountryCount
    : (typeof data.overlayDisplayedCountryCount === "number"
      ? data.overlayDisplayedCountryCount
      : countries.length);
  remainingEl.textContent = `${count} ${countriesWord(count)}`;
  applyDensityClass(countries.length);

  renderTopFive(data);
  renderRecord(data);
  updateStatsResetAvailability(data);
  syncReserveBatchFromSnapshot(data);

  if (countries.length === 0) {
    clearGridKeepCards();
    cardById.clear();
    lastOrderIds = [];
    previousById = new Map();
    if (phase === "IDLE" || phase === "BREAK") {
      showNote(phase === "BREAK" ? "Перерыв · ждём страны" : "Ждём участников", false);
    } else {
      showNote("Ждём участников", false);
    }
    updatePreviewDiag(data, [], false);
    return;
  }

  hideNote();
  detectEvents(countries);
  fillGrid(countries);
  lastOrderIds = countries.map((c) => c.id);

  const overflow = detectOverflow();
  updatePreviewDiag(data, countries, overflow);
}

let lastSuccessfulData = null;
let pollTimer = null;
let pollInFlight = false;
let pollAbort = null;
let backoffMs = POLL_MS;
const BACKOFF_MAX_MS = 10000;
const STATE_URLS = ["/arena/overlay-state", "/api/arena/state"];

const connectionStatusEl = document.getElementById("connection-status");

function setConnectionStatus(mode) {
  if (!connectionStatusEl) return;
  connectionStatusEl.textContent = mode;
  connectionStatusEl.classList.remove("online", "reconnect", "offline", "hidden");
  if (mode === "ONLINE") {
    connectionStatusEl.classList.add("online");
    if (!preview) connectionStatusEl.classList.add("hidden");
  } else if (mode === "RECONNECTING") {
    connectionStatusEl.classList.add("reconnect");
  } else {
    connectionStatusEl.classList.add("offline");
  }
}

async function pollOnce() {
  if (pollInFlight) return;
  pollInFlight = true;
  if (pollAbort) {
    try { pollAbort.abort(); } catch (_e) { /* ignore */ }
  }
  pollAbort = new AbortController();
  const timeoutId = setTimeout(() => pollAbort.abort(), Math.max(1500, POLL_MS * 4));
  try {
    let data = null;
    let lastError = null;
    for (const url of STATE_URLS) {
      try {
        const response = await fetch(url, { cache: "no-store", signal: pollAbort.signal });
        if (!response.ok) {
          lastError = new Error(`HTTP ${response.status}`);
          continue;
        }
        data = await response.json();
        break;
      } catch (err) {
        lastError = err;
      }
    }
    if (!data) {
      throw lastError || new Error("no_state");
    }
    backoffMs = POLL_MS;
    setConnectionStatus("ONLINE");
    if (!(lastSuccessfulData && typeof data.sequence === "number" && data.sequence === lastSequence)) {
      lastSequence = data.sequence ?? lastSequence;
      lastSuccessfulData = data;
      render(data);
    }
  } catch (_err) {
    setConnectionStatus(lastSuccessfulData ? "RECONNECTING" : "OFFLINE");
    if (!lastSuccessfulData) {
      showNote("Нет соединения с ареной", true);
    }
    if (backoffMs < RECONNECT_BASE_MS) {
      backoffMs = RECONNECT_BASE_MS;
    } else {
      backoffMs = Math.min(BACKOFF_MAX_MS, backoffMs * 2);
    }
  } finally {
    clearTimeout(timeoutId);
    pollInFlight = false;
    if (pollTimer != null) clearTimeout(pollTimer);
    pollTimer = setTimeout(pollOnce, backoffMs);
  }
}

preloadFlags();
setupResizeReposition();
setupDrag();
setConnectionStatus("ONLINE");
loadLayoutFromServer().finally(() => {
  pollOnce();
});

window.__arenaOverlayLayout = {
  formatWins,
  winsWord,
  DEFAULT_LAYOUT,
  LAYOUT_API,
  LAYOUT_RESET_API,
  clampFullyInside,
  pxFromRatio,
  ratioFromPx,
  layoutState,
  editMode,
  CHROMA_COLOR,
};

if (debug) {
  window.__tiktokOverlayDebug = Object.assign(debugStats, {
    getBackoffMs: () => backoffMs,
    hasLastState: () => !!lastSuccessfulData,
    isPolling: () => pollInFlight,
    layout: () => ({ ...layoutState }),
  });
  debugLog("debug mode on (?debug=1), flagFormat=PNG, native canvas (no CSS scale)");
}
