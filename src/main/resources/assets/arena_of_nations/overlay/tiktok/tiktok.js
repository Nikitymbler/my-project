const POLL_MS = 250;
const EVENT_MS = 3200;
const CANVAS_W = 1080;
const CANVAS_H = 1920;
/** Raster flags for CEF/TikTok LIVE Studio (copied from textures/gui/flags_hd 256×160). */
const FLAG_FORMAT = "PNG";
const FLAG_BASE = "/overlay/tiktok/flags";

const FLAG_IDS = [
  "ru", "ua", "by", "kz", "lt", "pl", "il", "am", "uz", "tj",
  "ge", "kg", "tm", "md", "az", "lv", "al", "bg", "cn", "us",
];

const params = new URLSearchParams(window.location.search);
const scaleParam = parseFloat(params.get("scale") || "1");
const preview = params.get("preview") === "1";
const debug = params.get("debug") === "1";

const debugStats = {
  cardsCreated: 0,
  snapshotsUpdated: 0,
  flagAssetAssignments: 0,
  flagFormat: FLAG_FORMAT,
};

function debugLog(message) {
  if (!debug) return;
  console.log(`[tiktok-overlay] ${message}`, { ...debugStats });
}

if (Number.isFinite(scaleParam) && scaleParam > 0.4 && scaleParam <= 2) {
  document.documentElement.style.setProperty("--overlay-scale", String(scaleParam));
}
if (preview) {
  document.body.classList.add("preview");
}

const fitRoot = document.getElementById("fit-root");
const brandEl = document.getElementById("brand");
const phaseEl = document.getElementById("phase");
const timerEl = document.getElementById("timer");
const remainingEl = document.getElementById("remaining");
const noteEl = document.getElementById("status-note");
const leftPanel = document.getElementById("left-panel");
const rightPanel = document.getElementById("right-panel");
const eventBanner = document.getElementById("event-banner");

/** @type {Map<string, HTMLElement>} Stable cards by country id */
const cardById = new Map();

let lastSequence = -1;
let previousById = new Map();
let eventQueue = [];
let eventBusy = false;
let flagsPreloaded = false;

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

function updateFitScale() {
  if (!preview || !fitRoot) {
    return;
  }
  const availableW = Math.max(1, window.innerWidth);
  const availableH = Math.max(1, window.innerHeight);
  const fit = Math.min(availableW / CANVAS_W, availableH / CANVAS_H);
  document.documentElement.style.setProperty("--fit-scale", String(fit));
}

function phaseLabel(phase) {
  switch (phase) {
    case "BATTLE": return "БОЙ";
    case "WAITING_FOR_OPPONENT": return "ОЖИДАНИЕ";
    case "BREAK": return "ПЕРЕРЫВ";
    case "IDLE": return "ЗАВЕРШЕНО";
    default: return phase || "—";
  }
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
  switch (country.status) {
    case "PROTECTED":
      return { text: "ЩИТ", cls: "status status-protected" };
    case "VULNERABLE":
      return { text: "УЯЗВИМА", cls: "status status-vulnerable status-long" };
    case "RESCUE":
      return {
        text: `${country.rescueSeconds || 0}с`,
        cls: "status status-rescue",
      };
    case "ELIMINATED":
      return { text: "ВЫБЫЛА", cls: "status status-eliminated status-long" };
    default:
      return { text: "?", cls: "status status-vulnerable" };
  }
}

function hpColor(country) {
  if (country.status === "ELIMINATED" || (country.corePercent ?? 0) <= 0) {
    return "#374151";
  }
  if (country.status === "RESCUE") return "#fb923c";
  const percent = country.corePercent ?? 0;
  if (percent > 60) return "#4ade80";
  if (percent >= 30) return "#facc15";
  return "#ef4444";
}

function hpPercent(country) {
  if (country.status === "ELIMINATED") return 0;
  return Math.max(0, Math.min(100, country.corePercent || 0));
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

  const top = document.createElement("div");
  top.className = "row-top";

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
  liveLabel.textContent = "БОЙ";
  const liveValue = document.createElement("span");
  liveValue.className = "stat-value";
  live.append(liveLabel, document.createTextNode(" "), liveValue);

  const reserve = document.createElement("div");
  reserve.className = "stat stat-reserve";
  const reserveLabel = document.createElement("span");
  reserveLabel.className = "stat-label";
  reserveLabel.textContent = "РЕЗ";
  const reserveValue = document.createElement("span");
  reserveValue.className = "stat-value";
  reserve.append(reserveLabel, document.createTextNode(" "), reserveValue);

  const status = document.createElement("div");

  top.append(flag, code, live, reserve, status);

  const bottom = document.createElement("div");
  bottom.className = "row-bottom";

  const hp = document.createElement("div");
  hp.className = "hp";
  const fill = document.createElement("div");
  fill.className = "hp-fill";
  hp.appendChild(fill);

  const hpNum = document.createElement("div");
  hpNum.className = "hp-num";

  bottom.append(hp, hpNum);
  el.append(top, bottom);

  el._refs = { flag, code, liveValue, reserveValue, status, fill, hpNum };
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

  // Only reassign if country id on this node changed (should not happen for stable cardById).
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

  const width = `${hpPercent(country)}%`;
  const color = hpColor(country);
  if (refs.fill.style.width !== width) {
    refs.fill.style.width = width;
  }
  if (refs.fill.style.backgroundColor !== color && refs.fill.style.background !== color) {
    refs.fill.style.background = color;
  }

  const hpText = `${country.coreHp} / ${country.coreMaxHp}`;
  if (refs.hpNum.textContent !== hpText) {
    refs.hpNum.textContent = hpText;
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
      enqueueEvent({
        title: name,
        subtitle: `СПАСЕНИЕ · ${c.rescueSeconds || 0}с`,
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

/**
 * Sync panel children to the ordered country list without recreating cards/flags.
 * Cards are reused from {@link cardById} by country id.
 */
function fillPanel(panel, list) {
  const desired = list.map((country) => ensureCard(country));
  let changed = false;

  for (let i = 0; i < desired.length; i++) {
    const want = desired[i];
    const current = panel.children[i];
    if (current !== want) {
      panel.insertBefore(want, current || null);
      changed = true;
    }
  }

  while (panel.children.length > desired.length) {
    panel.removeChild(panel.lastChild);
    changed = true;
  }

  if (changed && debug) {
    debugLog(`panel synced children=${panel.children.length}`);
  }
}

function clearPanelsKeepCards() {
  while (leftPanel.firstChild) leftPanel.removeChild(leftPanel.firstChild);
  while (rightPanel.firstChild) rightPanel.removeChild(rightPanel.firstChild);
}

function render(data) {
  debugStats.snapshotsUpdated += 1;
  if (debug && debugStats.snapshotsUpdated % 20 === 0) {
    debugLog("snapshot heartbeat");
  }

  brandEl.textContent = "ARENA OF NATIONS";
  phaseEl.textContent = phaseLabel(data.phase);
  const sec = Math.max(0, data.remainingSeconds || 0);
  const mm = String(Math.floor(sec / 60)).padStart(2, "0");
  const ss = String(sec % 60).padStart(2, "0");
  timerEl.textContent = `${mm}:${ss}`;

  const countries = Array.isArray(data.countries) ? data.countries.slice() : [];
  countries.sort((a, b) => (a.baseSlot ?? 999) - (b.baseSlot ?? 999));

  const count = data.activeCountryCount ?? countries.filter(c => !c.eliminated).length;
  remainingEl.textContent = `СТРАН: ${count}`;

  if ((data.activeCountryCount || 0) === 0 && countries.length === 0) {
    clearPanelsKeepCards();
    showNote("Раунд не начат", false);
    previousById = new Map();
    return;
  }

  hideNote();
  detectEvents(countries);

  fillPanel(leftPanel, countries.slice(0, 10));
  fillPanel(rightPanel, countries.slice(10, 20));
}

async function poll() {
  try {
    const response = await fetch("/api/arena/state", { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    if (typeof data.sequence === "number" && data.sequence === lastSequence) return;
    lastSequence = data.sequence ?? lastSequence;
    render(data);
  } catch (_err) {
    showNote("Нет соединения", true);
  }
}

preloadFlags();
window.addEventListener("resize", updateFitScale);
updateFitScale();
setInterval(poll, POLL_MS);
poll();

if (debug) {
  window.__tiktokOverlayDebug = debugStats;
  debugLog("debug mode on (?debug=1), flagFormat=PNG");
}
