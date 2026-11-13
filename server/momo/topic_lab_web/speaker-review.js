"use strict";
const reviewPanel=el("div","speaker-review-settings");
reviewPanel.innerHTML=`<h3>화자 매칭 재검토</h3>
<label for="speaker-review-mode">미확정 대사 처리</label>
<select id="speaker-review-mode"><option value="claude">임시 배정 + Claude 문맥 검토</option><option value="local">음성 근거로 임시 배정 (API 없이)</option><option value="conservative">근거 부족 시 미확정 유지</option></select>
<div id="claude-options"><label for="claude-model">검토용 Claude 모델</label><select id="claude-model"><option value="">API 키 연결 후 모델 선택</option><option value="__custom">모델 ID 직접 입력</option></select>
<input id="claude-custom" aria-label="Claude 모델 ID" placeholder="claude-…" maxlength="200" hidden>
<div class="segment-actions"><button type="button" class="secondary" id="claude-connection-button">Claude API 키 연결</button><button type="button" class="text-button" id="claude-refresh">모델 목록 새로고침</button></div></div>
<p class="hint">Claude에는 전사문·구간 시각·화자 후보 점수를 보내 문맥을 점검합니다. 음성 원본은 이 단계에서 보내지 않습니다. API 요금이 발생할 수 있으며 추정 배정은 확인이 필요합니다.</p>`;
$("segment-options").append(reviewPanel);
const claudeDialog=el("dialog");claudeDialog.id="claude-dialog";claudeDialog.setAttribute("aria-labelledby","claude-title");
claudeDialog.innerHTML=`<form id="claude-form"><div class="dialog-heading"><span class="eyebrow">CLAUDE CONNECTION</span><button id="claude-close" type="button" class="icon-button" aria-label="닫기">×</button></div><h2 id="claude-title">Anthropic API 키 연결</h2>
<p class="dialog-description">화자 문맥 검토에 사용합니다. 키는 현재 세션의 서버 메모리에만 보관합니다.</p>
<label for="claude-key">Anthropic API key</label><input id="claude-key" type="password" autocomplete="off" maxlength="512" placeholder="sk-ant-…">
<p class="hint">서버에 ANTHROPIC_API_KEY가 설정되어 있으면 비워도 됩니다. 키를 채팅에 보내지 마세요.</p>
<p id="claude-error" class="inline-error" role="alert" hidden></p><div class="dialog-actions"><button type="button" class="secondary" id="claude-disconnect">연결 해제</button><button class="primary" id="claude-connect" type="submit">키 연결</button></div></form>`;
document.body.append(claudeDialog);
function openClaudeConnection(){$("claude-error").hidden=true;claudeDialog.showModal();$("claude-key").focus();}
$("claude-connection-button").onclick=openClaudeConnection;
$("claude-close").onclick=()=>claudeDialog.close();
claudeDialog.addEventListener("close",()=>{$("claude-key").value="";});
$("speaker-review-mode").onchange=()=>{$("claude-options").hidden=$("speaker-review-mode").value!=="claude";};
$("claude-model").onchange=()=>{$("claude-custom").hidden=$("claude-model").value!=="__custom";};
async function refreshClaudeSession(){
  const data=await api("/api/claude/session");state.claudeConfigured=data.configured;
  $("claude-connection-button").textContent=data.configured?"Claude 키 연결됨":"Claude API 키 연결";
  $("claude-disconnect").hidden=!data.configured;
}
async function claudeModels(){
  const current=$("claude-model").value,data=await api("/api/claude/models");
  $("claude-model").replaceChildren(new Option("Claude 모델 선택",""));
  data.models.forEach(m=>$("claude-model").add(new Option(m.name,m.id)));
  $("claude-model").add(new Option("모델 ID 직접 입력","__custom"));
  if([...$("claude-model").options].some(o=>o.value===current))$("claude-model").value=current;
  $("claude-model").onchange();
}
$("claude-refresh").onclick=async()=>{if(!state.claudeConfigured)return openClaudeConnection();try{await claudeModels();notice("Claude 모델 목록을 불러왔습니다.","success");}catch(error){notice(error.message,"error");}};
$("claude-form").onsubmit=async event=>{
  event.preventDefault();$("claude-connect").disabled=true;$("claude-error").hidden=true;
  try{
    await api("/api/claude/connection",{method:"POST",body:JSON.stringify({api_key:$("claude-key").value})});
    $("claude-key").value="";await refreshClaudeSession();claudeDialog.close();
    try{await claudeModels();notice("Claude 키를 연결했습니다. 화자 검토 모델을 선택해 주세요.","success");}
    catch(error){notice(`${error.message} 모델 ID를 직접 입력할 수도 있습니다.`,"error");}
  }catch(error){$("claude-error").textContent=error.message;$("claude-error").hidden=false;}
  finally{$("claude-connect").disabled=false;}
};
$("claude-disconnect").onclick=async()=>{try{await api("/api/claude/connection",{method:"DELETE"});await refreshClaudeSession();claudeDialog.close();notice("Claude 키 연결을 해제했습니다.");}catch(error){notice(error.message,"error");}};
function speakerReviewSettings(){
  const mode=$("speaker-review-mode").value;
  if(mode!=="claude")return {mode};
  const model=$("claude-model").value==="__custom"?$("claude-custom").value.trim():$("claude-model").value;
  if(!/^claude-[a-zA-Z0-9_.-]+$/.test(model))throw new Error("화자 재검토에 사용할 Claude 모델을 선택하거나 ID를 입력해 주세요.");
  return {mode,model};
}
function renderSpeakerReview(run,root){
  const data=run.characters,review=run.speaker_review;
  root.append(el("p","hint",`추정 배정 ${data.provisional_segments||0}개 · 배정은 자막과 캐릭터 동작에 함께 적용됩니다. 추정 결과는 확정된 화자가 아닙니다.`));
  if(review){
    const labels={complete:"완료",partial:"일부 실패",failed:"실패"};
    root.append(el("p","hint",`${review.mode==="claude"?`Claude · ${review.model}`:review.mode==="local"?"음성 근거 임시 배정":"미확정 유지"} · ${labels[review.state]||review.state}${review.reused?" (기존 검토 재사용)":""} · Claude 검토 ${review.reviewed_segments||0}개 구간 · 호출 ${review.requests?.length||0}회`));
    for(const failure of review.failures||[])root.append(el("p","partial-banner",failure.error));
  }
  const button=el("button","secondary speaker-review-run","현재 설정으로 화자만 재검토");button.disabled=state.busy||!!run.partial;
  button.onclick=async()=>{
    if(state.busy)return;
    try{
      if($("speaker-review-mode").value==="claude"&&!state.claudeConfigured){openClaudeConnection();return;}
      const settings=speakerReviewSettings();busy(true);notice("저장된 전사와 음성 분석 근거로 화자를 재검토합니다. 완료되면 새 기록으로 저장됩니다.");
      const job=await api("/api/audio/jobs",{method:"POST",body:JSON.stringify({reuse_job_id:run.id,review_speakers:settings})});
      state.active=job.id;$("cancel").disabled=false;selectResultTab("characters");poll(job.id);
    }catch(error){busy(false);notice(error.message,"error");}
  };
  root.append(button,el("p","hint","왼쪽 ‘화자 매칭 재검토’ 설정을 사용합니다. 직접 수정한 화자는 유지합니다. 검토 후 ‘완성 영상’에서 영상을 다시 만들 수 있습니다."));
}
if(state.csrf)refreshClaudeSession().catch(error=>notice(error.message,"error"));
