const POLL_MS = 250;
let lastSequence = -1;

const titleEl = document.getElementById("title");
const timerEl = document.getElementById("timer");
const noteEl = document.getElementById("status-note");
const leftPanel = document.getElementById("left-panel");
const rightPanel = document.getElementById("right-panel");

function stateBadge(country) {
  switch (country.status) {
    case "PROTECTED": return { text: "ЩИТ", cls: "status-protected" };
    case "VULNERABLE": return { text: "!", cls: "status-vulnerable" };
    case "RESCUE": return { text: `${country.rescueSeconds || 0}с`, cls: "status-rescue" };
    case "ELIMINATED": return { text: "×", cls: "status-eliminated" };
    default: return { text: "?", cls: "status-vulnerable" };
  }
}

function hpColor(percent) {
  if (percent > 60) return "#4ade80";
  if (percent >= 30) return "#facc15";
  return "#ef4444";
}

function row(country) {
  const badge = stateBadge(country);
  const wrapper = document.createElement("div");
  wrapper.className = `row ${country.status === "ELIMINATED" ? "eliminated" : ""}`;
  wrapper.innerHTML = `
    <img class="flag" src="/overlay/flags/${country.id}.svg" alt="${country.code}">
    <div>${country.code}</div>
    <div>${country.activeFighters}/${country.reserve}</div>
    <div class="hp"><div class="hp-fill" style="width:${country.corePercent}%;background:${hpColor(country.corePercent)}"></div></div>
    <div>${country.coreHp}/${country.coreMaxHp}</div>
    <div class="${badge.cls}">${badge.text}</div>
  `;
  return wrapper;
}

function showNote(text, isError) {
  noteEl.textContent = text;
  noteEl.classList.toggle("hidden", false);
  noteEl.classList.toggle("error", !!isError);
}

function hideNote() {
  noteEl.classList.add("hidden");
  noteEl.classList.remove("error");
}

function render(data) {
  titleEl.textContent = data.title || `АРЕНА · ${data.phase || "IDLE"}`;
  const sec = Math.max(0, data.remainingSeconds || 0);
  const mm = String(Math.floor(sec / 60)).padStart(2, "0");
  const ss = String(sec % 60).padStart(2, "0");
  timerEl.textContent = `${mm}:${ss}`;

  const countries = Array.isArray(data.countries) ? data.countries : [];
  leftPanel.innerHTML = "";
  rightPanel.innerHTML = "";

  if ((data.activeCountryCount || 0) === 0 && countries.length === 0) {
    showNote("Раунд не начат", false);
    return;
  }

  hideNote();

  if (countries.length === 0) {
    showNote("Нет данных по странам", false);
    return;
  }

  if (countries.length <= 10) {
    countries.forEach((c, idx) => (idx % 2 === 0 ? leftPanel : rightPanel).appendChild(row(c)));
    return;
  }
  countries.slice(0, 10).forEach(c => leftPanel.appendChild(row(c)));
  countries.slice(10, 20).forEach(c => rightPanel.appendChild(row(c)));
}

async function poll() {
  try {
    const response = await fetch("/api/arena/state", { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    hideNote();
    if (typeof data.sequence === "number" && data.sequence === lastSequence) return;
    lastSequence = data.sequence ?? lastSequence;
    render(data);
  } catch (_err) {
    showNote("Нет соединения", true);
  }
}

setInterval(poll, POLL_MS);
poll();
