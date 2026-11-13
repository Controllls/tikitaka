"use strict";
const $ = (id) => document.getElementById(id);
const state = { csrf: "", configured: false, busy: false, runs: [], selected: null, tab: "topics", models: [] };
const emptyResult = $("result-content").firstElementChild.cloneNode(true);
const number = (value) => Number.isFinite(value) ? value.toLocaleString("ko-KR") : "—";
const elapsed = (ms) => (ms / 1000).toFixed(2);
const clock = (ms) => {
  if (ms == null) return "시간 없음";
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60).toString().padStart(2, "0")}:${(seconds % 60).toString().padStart(2, "0")}.${Math.floor(ms % 1000 / 100)}`;
};
function node(tag, className, text) {
  const item = document.createElement(tag);
  if (className) item.className = className;
  if (text != null) item.textContent = text;
  return item;
}
function notice(text, type = "") {
  $("notice").textContent = text;
  $("notice").className = `notice ${type}`;
  $("notice").hidden = !text;
}
async function api(path, options = {}) {
  let response;
  try {
    response = await fetch(path, {
      ...options, credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-Lab-Token": state.csrf, ...options.headers },
    });
  } catch {
    throw new Error("테스트 서버에 연결할 수 없습니다. topic-lab.ps1 실행 상태를 확인해 주세요.");
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(typeof data.detail === "string" ? data.detail : `요청 처리에 실패했습니다 (HTTP ${response.status}).`);
  return data;
}
async function loadSession() {
  const session = await api("/api/session");
  state.csrf = session.csrf;
  state.configured = session.configured;
  $("connection-status").textContent = session.configured ? "API 키 설정됨" : "API 키 연결";
  $("connection-dot").classList.toggle("connected", session.configured);
  $("disconnect").hidden = !session.configured;
  $("env-hint").hidden = !session.environment_available;
  $("prompt-version").textContent = `PROMPT v${session.prompt_version}`;
}
function selectedModel() {
  return $("model-select").value === "__custom" ? $("custom-model").value.trim() : $("model-select").value;
}
async function loadModels() {
  const previous = selectedModel();
  const data = await api("/api/models");
  state.models = data.models;
  $("model-select").replaceChildren(new Option("분석할 모델을 선택하세요", ""));
  for (const model of data.models) $("model-select").add(new Option(model, model));
  $("model-select").add(new Option("모델 ID 직접 입력", "__custom"));
  if (previous && data.models.includes(previous)) $("model-select").value = previous;
  else if (previous) { $("model-select").value = "__custom"; $("custom-model").value = previous; }
  $("custom-model-wrap").hidden = $("model-select").value !== "__custom";
  $("model-note").textContent = `${data.models.length}개 텍스트 모델 후보. ${data.note}`;
  $("connection-status").textContent = "OpenAI 연결됨";
}
function openConnection() {
  $("connection-error").hidden = true;
  $("connection-dialog").showModal();
  $("api-key").focus();
}
$("connection-button").addEventListener("click", openConnection);
$("close-dialog").addEventListener("click", () => $("connection-dialog").close());
$("connection-dialog").addEventListener("close", () => { $("api-key").value = ""; });
$("connection-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  $("connect").disabled = true;
  $("disconnect").disabled = true;
  $("connection-error").hidden = true;
  try {
    await api("/api/connection", { method: "POST", body: JSON.stringify({
      api_key: $("api-key").value, project_id: $("project-id").value.trim(), organization_id: $("organization-id").value.trim(),
    }) });
    $("api-key").value = "";
    await loadSession();
    $("connection-dialog").close();
    try {
      await loadModels();
      notice("OpenAI 모델 목록을 불러왔습니다. 모델을 선택하고 분석을 실행해 보세요.", "success");
      $("model-select").focus();
    } catch (error) {
      notice(`키는 설정했습니다. ${error.message} 목록 읽기 권한만 제한된 경우 모델 ID를 직접 입력할 수 있습니다.`, "error");
    }
  } catch (error) {
    $("connection-error").textContent = error.message;
    $("connection-error").hidden = false;
  } finally {
    $("connect").disabled = false;
    $("disconnect").disabled = false;
  }
});
$("disconnect").addEventListener("click", async () => {
  try {
    await api("/api/connection", { method: "DELETE" });
    await loadSession();
    $("connection-dialog").close();
    notice("이 브라우저 세션의 API 연결을 해제했습니다.");
  } catch (error) {
    $("connection-error").textContent = error.message;
    $("connection-error").hidden = false;
  }
});
$("model-select").addEventListener("change", () => {
  $("custom-model-wrap").hidden = $("model-select").value !== "__custom";
  if (!$("custom-model-wrap").hidden) $("custom-model").focus();
});
$("refresh-models").addEventListener("click", async () => {
  if (!state.configured) return openConnection();
  $("refresh-models").disabled = true;
  try { await loadModels(); notice("모델 목록을 새로 불러왔습니다.", "success"); }
  catch (error) { notice(error.message, "error"); }
  finally { $("refresh-models").disabled = false; }
});

function readWindows(text) {
  const data = JSON.parse(text || "[]");
  const rows = Array.isArray(data) ? data : data.windows;
  if (!Array.isArray(rows)) throw new Error("웃음 후보 JSON은 배열 또는 windows 배열을 가진 객체여야 합니다.");
  return rows;
}
function inputStats(sample = false) {
  const text = $("transcript").value.trim();
  $("input-count").textContent = `${number(text.length)}자`;
  $("input-kind").textContent = sample ? "가상 대화 · 샘플 시간 / 점수" : text.startsWith("{") || text.startsWith("[") ? "TRANSCRIPT JSON" : "TEXT · 주제 분할만";
  try { $("window-count").textContent = `${readWindows($("windows").value).length}개 구간`; }
  catch { $("window-count").textContent = "형식 확인"; }
}
function compactTranscript(data) {
  if (Array.isArray(data)) data = { utterances: data };
  if (!data || !Array.isArray(data.utterances)) throw new Error("utterances 배열이 있는 transcript.json을 선택해 주세요.");
  return "{\n" + (data.durationMs != null ? `  "durationMs": ${JSON.stringify(data.durationMs)},\n` : "")
    + '  "utterances": [\n' + data.utterances.map(u => "    " + JSON.stringify(u)).join(",\n") + "\n  ]\n}";
}
async function sample() {
  const response = await fetch("/static/sample.json");
  if (!response.ok) throw new Error("샘플을 불러오지 못했습니다.");
  const data = await response.json();
  $("transcript").value = compactTranscript(data);
  $("windows").value = JSON.stringify(data.windows, null, 2);
  $("threshold").value = "0.1";
  $("max-clips").value = "3";
  inputStats(true);
}
$("load-sample").addEventListener("click", async () => {
  try { await sample(); notice("샘플은 직접 작성한 가상 대화입니다. 시간과 웃음 점수도 실험용 예시이며, 분석 결과는 실제 API 호출로 생성됩니다."); }
  catch (error) { notice(error.message, "error"); }
});
$("transcript").addEventListener("input", () => inputStats());
$("windows").addEventListener("input", () => inputStats());
$("import-transcript").addEventListener("click", () => $("transcript-file").click());
$("import-windows").addEventListener("click", () => $("windows-file").click());
for (const kind of ["transcript", "windows"]) {
  $(`${kind}-file`).addEventListener("change", async (event) => {
    const file = event.target.files[0];
    if (!file) return;
    try {
      if (file.size > 800000) throw new Error("800 KB 이하의 파일을 선택해 주세요.");
      const text = (await file.text()).replace(/^\uFEFF/, "");
      if (kind === "windows") {
        $("windows").value = JSON.stringify(readWindows(text), null, 2);
        $("laughter-options").open = true;
      } else {
        if (/^\s*[\[{]/.test(text)) {
          const data = JSON.parse(text);
          $("transcript").value = compactTranscript(data);
          $("windows").value = JSON.stringify(data.windows || [], null, 2);
        } else {
          $("transcript").value = text;
          $("windows").value = "[]";
        }
      }
      inputStats();
      notice(`${file.name}을 열었습니다. 분석 버튼을 눌러야 OpenAI로 전송됩니다.`, "success");
    } catch (error) { notice(error.message, "error"); }
    finally { event.target.value = ""; }
  });
}

function setBusy(busy) {
  state.busy = busy;
  document.querySelectorAll(".input-panel button,.input-panel input,.input-panel select,.input-panel textarea").forEach(el => { el.disabled = busy; });
  $("connection-button").disabled = busy;
  $("clear-history").disabled = busy || !state.runs.length;
  $("run-label").textContent = busy ? "모델이 대화를 읽는 중…" : "이 모델로 분석하기";
  $("run-icon").textContent = busy ? "◌" : "▶";
  $("result-content").setAttribute("aria-busy", String(busy));
}
function metric(id, value, suffix = "") {
  $(id).replaceChildren(document.createTextNode(value));
  if (suffix) $(id).append(node("small", "", suffix));
}
function renderHeader(run) {
  $("result-state").textContent = run ? "ANALYSIS COMPLETE" : "READY TO EXPERIMENT";
  $("result-model").textContent = run ? run.model : "첫 번째 실험을 기다리는 중";
  $("api-badge").hidden = !run;
  $("export").disabled = !run;
  metric("metric-time", run ? elapsed(run.elapsed_ms) : "—", "초");
  metric("metric-tokens", run ? number(run.usage.total_tokens) : "—");
  metric("metric-topics", run ? String(run.topics.length) : "—", "개");
  metric("metric-clips", run ? String(run.clips.length) : "—", "개");
  $("usage-detail").textContent = run
    ? `입력 ${number(run.usage.input_tokens)} · 출력 ${number(run.usage.output_tokens)} · 캐시 ${number(run.usage.input_tokens_details?.cached_tokens)} · 추론 ${number(run.usage.output_tokens_details?.reasoning_tokens)} 토큰`
    : "전사문과 실험 기록은 이 페이지 메모리에만 유지됩니다.";
}
function topicCard(topic, index, clip = false) {
  const card = node("article", `topic-card${clip ? " clip-card" : ""}`);
  const heading = node("div", "card-heading");
  const title = node("div", "card-title");
  title.append(node("span", "topic-number", String(index + 1).padStart(2, "0")), node("h4", "", topic.title));
  heading.append(title, node("span", "range", topic.startMs != null ? `${clock(topic.startMs)} – ${clock(topic.endMs)}` : `발화 ${topic.start_id}–${topic.end_id}`));
  const foot = node("div", "card-footer");
  foot.append(node("span", "", `발화 #${clip ? topic.startUtteranceId : topic.start_id} – #${clip ? topic.endUtteranceId : topic.end_id}`));
  if (clip) {
    foot.append(node("span", "score", `최대 웃음 점수 ${topic.maxLaughter.toFixed(3)}`), node("span", "", `${topic.eventCount}개 웃음 이벤트 · ${elapsed(topic.durationMs)}초`));
  }
  card.append(heading, node("p", "card-reason", topic.reason), foot);
  if (topic.text) {
    const details = node("details", "transcript-detail");
    details.append(node("summary", "", "해당 대화 보기"), node("p", "", topic.text));
    card.append(details);
  }
  return card;
}
function messageState(title, description) {
  const wrap = node("div", "empty-state");
  wrap.append(node("h3", "", title), node("p", "", description));
  return wrap;
}
function renderCompare() {
  const root = $("result-content");
  if (state.runs.length < 2) {
    root.append(messageState("두 번 실행하면 비교할 수 있어요.", "같은 전사문으로 모델을 바꿔 실행하세요. 주제 경계·제목·선정 이유를 나란히 비교합니다."));
    return;
  }
  const controls = node("div", "compare-controls");
  const columns = node("div", "compare-grid");
  const warning = node("p", "compare-warning");
  const summary = node("p", "result-note");
  const selectors = ["compare-a", "compare-b"].map((id, index) => {
    const wrap = node("div");
    const label = node("label", "", index ? "결과 B" : "결과 A");
    label.htmlFor = id;
    const select = node("select");
    select.id = id;
    state.runs.forEach((run, i) => select.add(new Option(`${state.runs.length - i}. ${run.requested_model} · ${elapsed(run.elapsed_ms)}초`, run.id)));
    select.value = state.runs[index ? 0 : 1].id;
    wrap.append(label, select);
    controls.append(wrap);
    return select;
  });
  const update = () => {
    const runs = selectors.map(select => state.runs.find(run => run.id === select.value));
    const same = runs[0].input_hash === runs[1].input_hash && runs[0].prompt_version === runs[1].prompt_version;
    warning.hidden = same && runs[0].id !== runs[1].id;
    warning.textContent = runs[0].id === runs[1].id ? "같은 실행 결과를 선택했습니다. 다른 결과로 바꿔 보세요." : "두 실험의 입력 또는 선정 설정이 다릅니다. 동일한 입력으로 다시 실행하면 모델 차이를 비교하기 쉽습니다.";
    summary.textContent = `${same ? "입력·선정 설정 동일" : "입력·설정이 다른 실험"} · 출력 한도 A ${number(runs[0].max_output_tokens)} / B ${number(runs[1].max_output_tokens)}`;
    columns.replaceChildren();
    for (const run of runs) {
      const col = node("div", "compare-column");
      col.append(node("h3", "compare-title", run.requested_model), node("p", "result-note", `${elapsed(run.elapsed_ms)}초 · ${number(run.usage.total_tokens)} 토큰 · 주제 ${run.topics.length}개 · 클립 ${run.clips.length}개`));
      run.topics.forEach((topic, i) => col.append(topicCard(topic, i)));
      columns.append(col);
    }
  };
  selectors.forEach(select => select.addEventListener("change", update));
  root.append(controls, warning, summary, columns);
  update();
}
function renderResults() {
  const run = state.runs.find(item => item.id === state.selected);
  renderHeader(run);
  $("result-content").replaceChildren();
  if (state.tab === "compare") return renderCompare();
  if (!run) return $("result-content").append(emptyResult.cloneNode(true));
  const root = $("result-content");
  if (state.tab === "topics") {
    root.append(node("p", "result-note", run.timed ? "시작·종료 시간은 입력 전사의 발화 시각으로 계산했습니다." : "시간 정보가 없는 텍스트입니다. 발화 ID로 주제 경계만 표시합니다."));
    run.topics.forEach((topic, i) => root.append(topicCard(topic, i)));
  } else if (run.clips.length) {
    root.append(node("p", "result-note", `웃음 기준 ${run.input.threshold} · 이벤트 ${run.events.length}개 · 겹치지 않는 구간 최대 ${run.input.max_clips}개. 구간 선정 결과이며 오디오 파일을 생성하지는 않습니다.`));
    run.clips.forEach((clip, i) => root.append(topicCard(clip, i, true)));
  } else {
    const help = !run.timed ? "시간 정보가 있는 transcript.json과 웃음 후보를 넣으면 하이라이트 구간을 계산합니다."
      : !run.input.windows.length ? "웃음 후보를 넣지 않아 주제만 분할했습니다. YAMNet 구간 JSON을 추가하면 클립 후보를 계산합니다."
      : "웃음 기준과 음악 제외 조건을 통과한 구간이 없습니다. 입력 점수와 기준을 확인해 주세요.";
    root.append(messageState("선정된 하이라이트가 없습니다.", help));
  }
}
function activateTab(name) {
  state.tab = name;
  document.querySelectorAll("[data-tab]").forEach(button => {
    button.classList.toggle("active", button.dataset.tab === name);
    button.setAttribute("aria-selected", String(button.dataset.tab === name));
    button.tabIndex = button.dataset.tab === name ? 0 : -1;
  });
  $("result-content").setAttribute("aria-labelledby", `tab-${name}`);
  if (!state.busy) renderResults();
}
document.querySelectorAll("[data-tab]").forEach(button => {
  button.addEventListener("click", () => activateTab(button.dataset.tab));
  button.addEventListener("keydown", event => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const tabs = [...document.querySelectorAll("[data-tab]")];
    const index = event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : (tabs.indexOf(button) + (event.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length;
    activateTab(tabs[index].dataset.tab);
    tabs[index].focus();
  });
});
function renderHistory() {
  $("history-count").textContent = String(state.runs.length);
  $("compare-count").textContent = String(state.runs.length);
  $("clear-history").disabled = state.busy || !state.runs.length;
  $("history").replaceChildren();
  if (!state.runs.length) {
    $("history").append(node("p", "history-empty", "모델을 바꿔 실행해 보세요. 최근 10개 결과가 여기에 쌓입니다. 새로고침하면 기록이 지워집니다."));
    return;
  }
  const table = node("table");
  const head = node("thead");
  const headings = node("tr");
  ["실행 모델", "응답 시간", "사용 토큰", "주제 / 클립", "실행 시각"].forEach(text => headings.append(node("th", "", text)));
  head.append(headings);
  const body = node("tbody");
  for (const run of state.runs) {
    const row = node("tr", run.id === state.selected ? "selected-row" : "");
    const model = node("td");
    const select = node("button", "history-select", run.requested_model);
    select.title = "이 실험 결과 보기";
    select.disabled = state.busy;
    select.addEventListener("click", () => { state.selected = run.id; renderResults(); renderHistory(); });
    model.append(select);
    row.append(model, node("td", "", `${elapsed(run.elapsed_ms)}초`), node("td", "", number(run.usage.total_tokens)), node("td", "", `${run.topics.length} / ${run.clips.length}`), node("td", "", new Date(run.created_at).toLocaleTimeString("ko-KR", { hour12: false })));
    body.append(row);
  }
  table.append(head, body);
  const wrap = node("div", "history-list");
  wrap.append(table);
  $("history").append(wrap);
}
async function runExperiment() {
  if (state.busy) return;
  if (!state.configured) return openConnection();
  let payload;
  try {
    if (!selectedModel()) { $("model-select").focus(); throw new Error("분석 모델을 선택하거나 모델 ID를 입력해 주세요."); }
    if (!$("transcript").value.trim()) throw new Error("분석할 전사문을 입력해 주세요.");
    let windows;
    try { windows = readWindows($("windows").value); }
    catch { throw new Error("웃음 후보 JSON 형식을 확인해 주세요. 주제만 테스트하려면 []를 입력하세요."); }
    payload = { model: selectedModel(), transcript: $("transcript").value, windows,
      threshold: Number($("threshold").value), max_clips: Number($("max-clips").value), max_output_tokens: Number($("output-tokens").value) };
    if (windows.length && !/^\s*[\[{]/.test(payload.transcript)) throw new Error("시간 없는 텍스트는 주제 분할만 가능합니다. ‘웃음 후보 연결’을 열어 후보를 []로 비워 주세요.");
  } catch (error) { notice(error.message, "error"); return; }
  notice("");
  setBusy(true);
  renderHistory();
  renderHeader(null);
  $("result-state").textContent = "REQUESTING OPENAI";
  $("result-model").textContent = payload.model;
  const loading = node("div", "loading");
  const time = node("p", "", "실제 OpenAI API에 요청하고 있습니다. 자동 재호출은 하지 않습니다.");
  loading.append(node("div", "spinner"), node("h3", "", "대화 속 주제 경계를 찾고 있어요."), time);
  $("result-content").replaceChildren(loading);
  const started = Date.now();
  const timer = setInterval(() => { time.textContent = `${Math.floor((Date.now() - started) / 1000)}초 경과 · 모델 응답을 기다리고 있습니다. 페이지를 유지해 주세요.`; }, 1000);
  try {
    const result = await api("/api/analyze", { method: "POST", body: JSON.stringify(payload) });
    state.runs.unshift(result);
    state.runs.length = Math.min(state.runs.length, 10);
    state.selected = result.id;
    $("connection-status").textContent = "OpenAI 연결됨";
    notice(`${result.requested_model} 분석 완료 · ${result.topics.length}개 주제 · ${result.clips.length}개 하이라이트`, "success");
  } catch (error) { notice(error.message, "error"); }
  finally {
    clearInterval(timer);
    setBusy(false);
    renderResults();
    renderHistory();
  }
}
$("run").addEventListener("click", runExperiment);
document.addEventListener("keydown", event => {
  if ((event.ctrlKey || event.metaKey) && event.key === "Enter" && !$("connection-dialog").open) {
    event.preventDefault(); runExperiment();
  }
});
$("clear-history").addEventListener("click", () => {
  state.runs = []; state.selected = null;
  renderResults(); renderHistory(); notice("이 페이지의 실험 기록을 비웠습니다.");
});
$("export").addEventListener("click", () => {
  const run = state.runs.find(item => item.id === state.selected);
  if (!run) return;
  const url = URL.createObjectURL(new Blob([JSON.stringify(run, null, 2)], { type: "application/json;charset=utf-8" }));
  const link = node("a");
  link.href = url;
  link.download = `topic-lab-${run.requested_model.replace(/[^a-zA-Z0-9_.-]/g, "_")}-${run.id}.json`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});
(async () => {
  const outcomes = await Promise.allSettled([loadSession(), sample()]);
  const error = outcomes.find(result => result.status === "rejected");
  if (error) { notice(error.reason.message, "error"); return; }
  if (state.configured) {
    try { await loadModels(); }
    catch (error) { notice(error.message, "error"); }
  } else notice("샘플 대화가 준비되었습니다. 우측 상단에서 API 키를 연결한 뒤 모델을 골라 실행하세요.");
})();
