const POLL_MS = 250;
const EVENT_MS = 3200;

const params = new URLSearchParams(window.location.search);
const scaleParam = parseFloat(params.get("scale") || "1");
const preview = params.get("preview") === "1";

if (Number.isFinite(scaleParam) && scaleParam > 0.4 && scaleParam <= 2) {
  document.documentElement.style.setProperty("--overlay-scale", String(scaleParam));
}
if (preview) {
  document.body.classList.add("preview");
}

const brandEl = document.getElementById("brand");
const phaseEl = document.getElementById("phase");
const timerEl = document.getElementById("timer");
const remainingEl = document.getElementById("remaining");
const noteEl = document.getElementById("status-note");
const leftPanel = document.getElementById("left-panel");
const rightPanel = document.getElementById("right-panel");
const eventBanner = document.getElementById("event-banner");

let lastSequence = -1;
let previousById = new Map();
let eventQueue = [];
let eventBusy = false;
let compact = false;

function updateCompactMode() {
  const panelWidth = leftPanel.getBoundingClientRect().width || 300;
  compact = panelWidth < 270 || window.innerWidth < 820;
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

function statusBadge(country) {
  switch (country.status) {
    case "PROTECTED":
      return { text: "ЩИТ", cls: "status-protected" };
    case "VULNERABLE":
      return { text: compact ? "!" : "УЯЗВИМА", cls: "status-vulnerable" };
    case "RESCUE":
      return {
        text: compact ? `${country.rescueSeconds || 0}с` : `СПАСЕНИЕ ${country.rescueSeconds || 0}с`,
        cls: "status-rescue",
      };
    case "ELIMINATED":
      return { text: compact ? "×" : "ВЫБЫЛА", cls: "status-eliminated" };
    default:
      return { text: "?", cls: "status-vulnerable" };
  }
}

function hpColor(country) {
  if (country.status === "ELIMINATED") return "#6b7280";
  if (country.status === "RESCUE") return "#fb923c";
  const percent = country.corePercent ?? 0;
  if (percent > 60) return "#4ade80";
  if (percent >= 30) return "#facc15";
  return "#ef4444";
}

function fightersText(country) {
  const active = country.activeFighters ?? 0;
  const reserve = country.reserve ?? 0;
  if (compact) return `${active}/${reserve}`;
  return `${active} + ${reserve}`;
}

function row(country) {
  const badge = statusBadge(country);
  const el = document.createElement("div");
  el.className = `row ${compact ? "compact" : ""} ${country.status === "ELIMINATED" ? "eliminated" : ""} ${country.status === "RESCUE" ? "rescue" : ""}`;
  el.dataset.id = country.id;
  el.innerHTML = `
    <img class="flag" src="/overlay/flags/${country.id}.svg" alt="${country.code}">
    <div class="code">${country.code}</div>
    <div class="fighters">${fightersText(country)}</div>
    <div class="hp"><div class="hp-fill" style="width:${Math.max(0, Math.min(100, country.corePercent || 0))}%;background:${hpColor(country)}"></div></div>
    <div class="hp-num">${country.coreHp}/${country.coreMaxHp}</div>
    <div class="${badge.cls}">${badge.text}</div>
  `;
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

function enqueueEvent(text) {
  if (!text) return;
  eventQueue.push(text);
  pumpEvents();
}

function pumpEvents() {
  if (eventBusy || eventQueue.length === 0) return;
  eventBusy = true;
  const text = eventQueue.shift();
  eventBanner.textContent = text;
  eventBanner.classList.remove("hidden");
  setTimeout(() => {
    eventBanner.classList.add("hidden");
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
      enqueueEvent(`${name} ВЫБЫЛА`);
    } else if (prev.status !== "RESCUE" && c.status === "RESCUE") {
      enqueueEvent(`СПАСЕНИЕ ${name} — ${c.rescueSeconds || 0} СЕКУНД`);
    } else if (prev.activeFighters > 0 && c.activeFighters === 0 && !c.eliminated && c.status !== "RESCUE") {
      enqueueEvent(`${name} ОСТАЛАСЬ БЕЗ ЗАЩИТНИКОВ`);
    } else {
      const deltaReserve = (c.reserve || 0) - (prev.reserve || 0);
      if (deltaReserve >= 10) {
        enqueueEvent(`ПОДКРЕПЛЕНИЕ: ${name} +${deltaReserve}`);
      }
    }
  }
  if (previousById.size > 0) {
    // victory: only one non-eliminated left
    const alive = countries.filter(c => !c.eliminated);
    if (alive.length === 1) {
      const winner = alive[0];
      const prevAlive = [...previousById.values()].filter(c => !c.eliminated);
      if (prevAlive.length > 1) {
        enqueueEvent(`ПОБЕДА: ${(winner.name || winner.code || "").toUpperCase()}`);
      }
    }
  }
  previousById = next;
}

function fillPanel(panel, list) {
  const existing = new Map();
  for (const child of panel.children) {
    if (child.dataset && child.dataset.id) existing.set(child.dataset.id, child);
  }
  const keep = new Set(list.map(c => c.id));
  for (const [id, node] of existing) {
    if (!keep.has(id)) node.remove();
  }
  list.forEach((country, index) => {
    const next = row(country);
    const current = panel.children[index];
    if (!current) {
      panel.appendChild(next);
      return;
    }
    if (current.dataset.id !== country.id) {
      panel.replaceChild(next, current);
      return;
    }
    // In-place update without full remount when same country.
    current.className = next.className;
    current.innerHTML = next.innerHTML;
  });
}

function render(data) {
  updateCompactMode();
  brandEl.textContent = "ARENA OF NATIONS";
  phaseEl.textContent = phaseLabel(data.phase);
  const sec = Math.max(0, data.remainingSeconds || 0);
  const mm = String(Math.floor(sec / 60)).padStart(2, "0");
  const ss = String(sec % 60).padStart(2, "0");
  timerEl.textContent = `${mm}:${ss}`;

  const countries = Array.isArray(data.countries) ? data.countries.slice() : [];
  countries.sort((a, b) => (a.baseSlot ?? 999) - (b.baseSlot ?? 999));

  remainingEl.textContent = `Стран: ${data.activeCountryCount ?? countries.filter(c => !c.eliminated).length}`;

  if ((data.activeCountryCount || 0) === 0 && countries.length === 0) {
    leftPanel.innerHTML = "";
    rightPanel.innerHTML = "";
    showNote("Раунд не начат", false);
    previousById = new Map();
    return;
  }

  hideNote();
  detectEvents(countries);

  const left = countries.slice(0, 10);
  const right = countries.slice(10, 20);
  fillPanel(leftPanel, left);
  fillPanel(rightPanel, right);
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

window.addEventListener("resize", updateCompactMode);
updateCompactMode();
setInterval(poll, POLL_MS);
poll();
