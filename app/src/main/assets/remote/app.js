'use strict';
const $ = id => document.getElementById(id);
let token = localStorage.getItem('s1-token') || '';
let snapshot = null, received = 0, pollHandle, busy = false, taskKey = '', alarmKey = '';
function message(text) { $('message').textContent = text; }
function paired(value) { $('pairing').hidden = value; $('controls').hidden = !value; }
async function request(path, method = 'GET', body) {
  const abort = new AbortController(), timeout = setTimeout(() => abort.abort(), 7000);
  try {
    const response = await fetch('/api/' + path, {method, signal: abort.signal, cache:'no-store',
      headers: {'Content-Type':'application/json', ...(token ? {'Authorization':'Bearer ' + token} : {})},
      body: body === undefined ? undefined : JSON.stringify(body)});
    const data = await response.json();
    if (!response.ok) {
      if (response.status === 401 && path !== 'pair') {
        token = ''; localStorage.removeItem('s1-token'); snapshot = null; paired(false);
      }
      throw new Error(data.error || 'Unable to apply change');
    }
    return data;
  } finally { clearTimeout(timeout); }
}
function duration(ms, roundUp) {
  const s = Math.max(0, (roundUp ? Math.ceil : Math.floor)(ms / 1000));
  return [Math.floor(s/3600), Math.floor(s/60)%60, s%60].map(x => String(x).padStart(2,'0')).join(':');
}
function clocks() {
  if (!snapshot) return;
  const delta = performance.now() - received;
  $('timer-value').textContent = duration(snapshot.timer.remainingMs - (snapshot.timer.mode === 'running' ? delta : 0), true);
  $('watch-value').textContent = duration(snapshot.stopwatch.elapsedMs + (snapshot.stopwatch.running ? delta : 0), false);
}
function text(tag, value, css) { const node = document.createElement(tag); node.textContent = value; if(css) node.className = css; return node; }
function button(label, callback) { const node = text('button', label, 'quiet'); node.type = 'button'; node.onclick = callback; return node; }
function render(data) {
  snapshot = data; received = performance.now(); paired(true); $('connection').textContent = 'Connected to S1';
  $('alert').hidden = !data.ringing; $('alert-text').textContent = data.ringing; $('audio-issue').textContent = data.audioIssue;
  $('timer-mode').textContent = data.timer.mode;
  $('watch-mode').textContent = data.stopwatch.running ? 'Running' : 'Paused';
  $('exact-warning').hidden = data.exactAlarms;
  $('sound').value = data.customSound ? 'custom' : 'default'; $('sound').options[1].disabled = !data.hasCustomSound;
  document.querySelector('[data-timer="pause"]').disabled = data.timer.mode !== 'running';
  document.querySelector('[data-timer="resume"]').disabled = data.timer.mode !== 'paused';
  const tk = JSON.stringify(data.tasks);
  if (tk !== taskKey) {
    taskKey = tk; $('task-list').replaceChildren();
    $('task-count').textContent = data.tasks.filter(t => !t.completed).length + ' remaining';
    if(!data.tasks.length) $('task-list').append(text('li','No tasks yet','empty'));
    data.tasks.forEach(task => {
      const row = document.createElement('li'); row.className = task.completed ? 'completed' : task.overdue ? 'overdue' : '';
      const check = document.createElement('input'); check.type='checkbox'; check.checked=task.completed;
      check.setAttribute('aria-label', 'Complete ' + task.title);
      check.onchange = () => command('tasks/'+task.id, 'PUT', {completed:check.checked});
      const copy = text('div','','task-copy'); copy.append(text('span',task.title,'task-title'));
      const parts = task.due.split('T');
      copy.append(text('span',(task.overdue?'Overdue · ':'') + parts[1].slice(0,5) + (parts[0] !== data.localDate?' · '+parts[0]:''),'due'));
      row.append(check,copy,button('Delete',() => { if(confirm('Delete “'+task.title+'”?')) command('tasks/'+task.id,'DELETE'); }));
      $('task-list').append(row);
    });
  }
  const ak = JSON.stringify(data.alarms);
  if(ak !== alarmKey) {
    alarmKey = ak; $('alarm-list').replaceChildren();
    if(!data.alarms.length) $('alarm-list').append(text('li','No alarms yet','empty'));
    data.alarms.forEach(alarm => {
      const row = document.createElement('li'), toggle=document.createElement('input');
      toggle.type='checkbox'; toggle.checked=alarm.enabled; toggle.setAttribute('aria-label','Enable '+alarm.time+' alarm');
      toggle.onchange=()=>command('alarms/'+alarm.id,'PUT',{enabled:toggle.checked});
      row.append(toggle,text('span',alarm.time,'task-copy'),button('Delete',()=>{if(confirm('Delete this alarm?')) command('alarms/'+alarm.id,'DELETE');}));
      $('alarm-list').append(row);
    });
  }
  clocks();
}
function schedule() { clearTimeout(pollHandle); if(token && !document.hidden) pollHandle=setTimeout(poll,5000); }
async function poll() {
  if(busy || !token || document.hidden) { schedule(); return; }
  busy=true;
  try { render(await request('status')); message(''); }
  catch(e) { $('connection').textContent='Disconnected'; message(e.name==='AbortError'?'S1 is not responding. Reconnecting…':e.message); }
  finally { busy=false; schedule(); }
}
async function command(path, method='POST', body={}) {
  if(busy) { taskKey=''; alarmKey=''; message('A request is in progress. Please try again in a moment.'); return false; }
  busy=true; clearTimeout(pollHandle);
  try { render(await request(path,method,body)); message(''); return true; }
  catch(e) { message(e.name==='AbortError'?'Connection timed out. Check the current state before retrying.':e.message); taskKey=''; alarmKey=''; return false; }
  finally { busy=false; schedule(); }
}
$('pair-form').onsubmit=async event=>{
  event.preventDefault();
  try { const data=await request('pair','POST',{code:$('code').value}); token=data.token; localStorage.setItem('s1-token',token); taskKey='';alarmKey='';await poll(); }
  catch(e) { message(e.name==='AbortError'?'Cannot reach the S1. Check Wi-Fi.':e.message); }
};
$('task-form').onsubmit=async event=>{event.preventDefault();if(await command('tasks','POST',{title:$('task-title').value,time:$('task-time').value})) $('task-title').value='';};
$('timer-form').onsubmit=event=>{event.preventDefault();command('timer/set','POST',{seconds:Number($('minutes').value)*60+Number($('seconds').value)});};
$('alarm-form').onsubmit=event=>{event.preventDefault();command('alarms','POST',{time:$('alarm-time').value});};
document.querySelectorAll('[data-timer]').forEach(node=>node.onclick=()=>command('timer/'+node.dataset.timer));
document.querySelectorAll('[data-watch]').forEach(node=>node.onclick=()=>command('stopwatch/'+node.dataset.watch));
$('dismiss').onclick=()=>command('alerts/dismiss');
$('sound').onchange=()=>command('sound','PUT',{custom:$('sound').value==='custom'});
$('forget').onclick=()=>{token='';snapshot=null;localStorage.removeItem('s1-token');clearTimeout(pollHandle);paired(false);$('connection').textContent='Not paired';message('');};
document.addEventListener('visibilitychange',()=>{if(document.hidden) clearTimeout(pollHandle);else poll();});
window.addEventListener('online',poll);
// Local rendering only; network synchronization is once every five seconds.
setInterval(()=>{if(!document.hidden)clocks();},1000);
paired(false); if(token) poll();
