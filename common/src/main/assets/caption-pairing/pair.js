(function () {
  'use strict';
  var zh = /^zh/i.test(navigator.language || '');
  var copy = {
    title: ['Subtitle connection', '电视字幕连接'],
    network: ['Use your own trusted home network. Local HTTP does not encrypt the connection details sent to your TV.', '仅在自家可信网络使用。局域网 HTTP 不加密手机发给电视的连接信息。'],
    loading: ['Reading the TV settings…', '正在读取电视设置…'],
    connection: ['Connection', '模型连接'], endpoint: ['Complete API endpoint', '完整 API 接口地址'], model: ['Model ID', '模型名称'],
    key: ['Key', 'Key'], keep: ['Keep saved Key', '保留电视已存 Key'], replace: ['Use a new Key', '填写新 Key'], clearKey: ['Remove Key', '清除 Key'],
    newKey: ['New Key', '新 Key'], keyHint: ['The TV never returns your saved Key to this page.', '电视不会把已保存的 Key 返回到此页面。'],
    endpointChanged: ['Changing the endpoint requires a new Key or explicitly removing the old one.', '更换接口地址时，需要填写新 Key 或明确清除旧 Key。'],
    advanced: ['Output and model parameters', '输出与模型参数'], tokens: ['Maximum output tokens', '最大输出 token 数'], thinking: ['Thinking parameter', '思考参数'],
    thinkingDefault: ['Do not add a parameter', '不额外发送参数'], thinkingDisabled: ['DeepSeek: send disabled', 'DeepSeek：发送 disabled'],
    modelHint: ['Limits and parameters must match your model. Saving alone does not verify model access.', '参数和上限须符合所选模型。保存配置不代表模型已验证可用。'],
    test: ['Test connection', '测试连接'], save: ['Save on TV', '保存到电视'],
    testHint: ['Testing sends one small model request, with at most 64 output tokens. It does not save the form.', '测试会发送一次小请求，最多 64 个输出 token，不保存表单。'],
    removeTitle: ['Remove this connection', '移除整个连接'], removeHint: ['Remove the endpoint, model and Key from the TV. Subtitle display preferences stay unchanged.', '从电视移除接口、模型和 Key；字幕显示偏好保持不变。'],
    remove: ['Remove connection', '移除连接'], cancel: ['End pairing', '结束配对'],
    footer: ['Display mode and channel translation remain in the TV subtitle menu. This page uses no external resources or browser storage.', '显示模式与频道翻译开关仍在电视字幕菜单中设置。本页不使用外部资源或浏览器存储。'],
    noToken: ['Scan the QR code shown on your TV. Entering the address alone does not include the pairing credential.', '请扫描电视显示的二维码。仅输入地址不包含配对凭据。'],
    ready: ['Review the values, then test or save on your TV.', '核对内容后，可测试连接或保存到电视。'],
    changed: ['Values changed. This version has not been tested or saved.', '内容已修改，当前版本尚未测试或保存。'],
    invalidState: ['The TV could not read all settings. Correct the values and retry, or explicitly remove the connection.', '电视未能完整读取设置。可修正后重试，或明确移除连接。'],
    missing: ['No Key is saved. Translation requests need a Key.', '尚未保存 Key，翻译请求需要 Key。'],
    persistent: ['A Key is saved on the TV. It is not shown here.', '电视已保存 Key，此处不回显。'],
    memory: ['Key is in memory only. Restarting the TV app requires entering it again.', 'Key 仅保存在进程内存，重启电视应用后需要重新填写。'],
    testing: ['Testing… No settings have been saved.', '正在测试… 尚未保存设置。'], saving: ['Saving on TV…', '正在保存到电视…'],
    tested: ['Connection test passed. The form has not been saved.', '连接测试通过，表单尚未保存。'],
    truncated: ['The service replied, but the small test output was truncated. Check the model parameters; nothing was saved.', '服务已响应，但小测试的输出被截断。请检查模型参数，尚未保存。'],
    testFailed: ['The test did not complete successfully. Check the endpoint, model, Key and parameters. Nothing was saved.', '测试未成功完成。请检查接口、模型、Key 和参数，尚未保存。'],
    saved: ['Saved on your TV. Pairing has ended.', '已保存到电视，配对已结束。'],
    savedMissing: ['Connection saved without a Key. Translation will not send requests. Reopen pairing to add a Key.', '连接已保存，但没有 Key，不会发送翻译请求。需添加 Key 时请在电视重新配对。'],
    savedMemory: ['Saved for this app process only. Secure storage is unavailable; enter the Key again after restarting.', '已保存，但 Key 仅供本次进程使用；安全存储不可用，重启后需要重新填写。'],
    cleared: ['Connection removed from your TV. Reopen pairing if you want to configure it again.', '电视连接已移除。需要重新配置时，请在电视重新打开配对。'],
    writeFailed: ['Saving was not completed. Check the TV and retry; this page does not assume success.', '保存未完成。请核对电视状态后重试，本页不会把提交当作成功。'],
    partial: ['The connection changed, but the remaining save did not finish. Review the newly loaded values and explicitly save again.', '连接已有部分变更，其余保存未完成。请核对重新载入的内容，再明确点击保存。'],
    conflict: ['TV settings changed. Review the reloaded values before saving again; your typed Key was cleared.', '电视设置已变更。请核对重新载入的内容后再保存；刚填写的 Key 已清空。'],
    expired: ['Pairing has ended or is no longer current. Reopen it on your TV and scan the new QR code.', '配对已结束或已失效。请在电视重新打开，并扫描新的二维码。'],
    unknown: ['No save confirmation was received. This does not mean nothing was saved. Check the TV before reopening pairing.', '未收到保存确认，这不代表没有保存。请先查看电视状态，再重新配对。'],
    unreachable: ['Cannot reach the TV. Check the same home network and reopen pairing if it expired.', '无法连接电视。请确认在同一家庭网络；如已过期，请在电视重新配对。'],
    badInput: ['Check the fields. The endpoint must be HTTPS; Key is not trimmed and cannot contain spaces or line breaks.', '请检查填写内容。模型接口需为 HTTPS；Key 不会自动裁剪，不能包含空格或换行。'],
    busy: ['A request is still being handled. Do not submit it repeatedly; check the TV or reopen pairing.', '一个请求仍在处理中。请勿反复提交；先查看电视状态或重新配对。'],
    ended: ['Pairing ended. Use the TV menu to start a new session.', '配对已结束，需要时可从电视菜单重新开启。'],
    ending: ['Ending pairing…', '正在结束配对…'],
    endUnknown: ['This page has closed, but the TV did not confirm. Close pairing on the TV; it also expires automatically.', '手机页已退出，但电视未返回确认。请在电视关闭配对；会话也会自动过期。'],
    cancelPending: ['A save may already have started. Ending pairing does not undo a committed change. End pairing?', '保存可能已开始，结束配对不会撤销已提交的修改。是否结束配对？'],
    confirmClear: ['Remove the endpoint, model and Key from the TV?', '确认从电视移除接口地址、模型和 Key？'],
    confirmKey: ['Remove the saved Key while keeping the endpoint and model?', '确认清除已存 Key，同时保留接口和模型？']
  };
  function t(name) { var text = copy[name]; return text ? text[zh ? 1 : 0] : name; }
  function byId(id) { return document.getElementById(id); }
  document.documentElement.lang = zh ? 'zh-CN' : 'en';
  document.title = t('title');
  var localized = document.querySelectorAll('[data-i18n]');
  for (var i = 0; i < localized.length; i++) localized[i].textContent = t(localized[i].getAttribute('data-i18n'));
  byId('peer').textContent = location.host;
  var token = location.hash.substring(1), state = null, busy = false, ended = false;
  try { history.replaceState(null, '', '/'); } catch (ignored) { }
  function status(name, kind) {
    byId('status').textContent = t(name);
    byId('status').className = 'status' + (kind ? ' ' + kind : '');
  }
  function mode() { var chosen = document.querySelector('input[name="key-edit"]:checked'); return chosen ? chosen.value : 'REPLACE'; }
  function update() {
    var locked = busy || ended || !state;
    byId('connection-fields').disabled = locked;
    byId('replacement-wrap').hidden = mode() !== 'REPLACE';
    byId('replacement').disabled = locked || mode() !== 'REPLACE';
    byId('replacement').required = mode() === 'REPLACE';
    byId('remove').disabled = locked;
    byId('test').disabled = locked || mode() === 'CLEAR' || mode() === 'KEEP' && state && state.keyState === 'NONE';
    var previous = state && state.connection;
    byId('endpoint-warning').hidden = !(previous && state.keyState !== 'NONE' && mode() === 'KEEP' && byId('endpoint').value.trim() !== previous.endpoint);
  }
  function finish(name, kind) {
    ended = true; busy = false; token = ''; byId('replacement').value = ''; update(); status(name, kind);
  }
  function request(path, payload, done) {
    var xhr = new XMLHttpRequest();
    xhr.open('POST', path, true);
    xhr.timeout = 15000;
    xhr.setRequestHeader('Authorization', 'Pair ' + token);
    xhr.setRequestHeader('Content-Type', 'application/json; charset=utf-8');
    var completed = false;
    function complete(code, body) { if (completed) return; completed = true; done(code, body); }
    xhr.onload = function () {
      var body = null;
      try { body = JSON.parse(xhr.responseText); } catch (ignored) { }
      complete(xhr.status, body);
    };
    xhr.onerror = xhr.ontimeout = function () { complete(0, null); };
    try { xhr.send(JSON.stringify(payload)); } catch (ignored) { complete(0, null); }
  }
  function keyState(value) {
    if (['NONE', 'PERSISTENT', 'MEMORY_ONLY'].indexOf(value) < 0) return false;
    if (state) state.keyState = value;
    byId('key-state').textContent = t(value === 'PERSISTENT' ? 'persistent' : value === 'MEMORY_ONLY' ? 'memory' : 'missing');
    return true;
  }
  function edited() { if (!busy && !ended && state) { status('changed'); update(); } }
  function applyState(next) {
    if (!next || next.version !== 1 || typeof next.revision !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(next.revision) ||
        typeof next.valid !== 'boolean' || ['NONE', 'PERSISTENT', 'MEMORY_ONLY'].indexOf(next.keyState) < 0) return false;
    if (next.connection !== null && (!next.connection || typeof next.connection.endpoint !== 'string' ||
        typeof next.connection.model !== 'string' || typeof next.connection.tokens !== 'number' ||
        ['OMIT', 'DEEPSEEK_DISABLED'].indexOf(next.connection.thinking) < 0)) return false;
    state = next;
    var connection = next.connection;
    byId('endpoint').value = connection ? connection.endpoint : '';
    byId('model').value = connection ? connection.model : '';
    byId('tokens').value = connection ? connection.tokens : 1024;
    byId('thinking').value = connection && connection.thinking === 'DEEPSEEK_DISABLED' ? 'DEEPSEEK_DISABLED' : 'OMIT';
    byId('replacement').value = '';
    var selected = next.keyState === 'NONE' ? 'REPLACE' : 'KEEP';
    document.querySelector('input[name="key-edit"][value="' + selected + '"]').checked = true;
    keyState(next.keyState);
    update();
    return true;
  }
  function payload(operation) {
    return {version: 1, revision: state.revision, operation: operation,
      endpoint: byId('endpoint').value.trim(), model: byId('model').value.trim(), tokens: Number(byId('tokens').value),
      thinking: byId('thinking').value, keyEdit: mode(), key: mode() === 'REPLACE' ? byId('replacement').value : null};
  }
  function submit(operation) {
    var formElement = byId('connection-form');
    if (busy || ended || !state) return;
    if (formElement.reportValidity ? !formElement.reportValidity() : !formElement.checkValidity()) { status('badInput', 'error'); return; }
    if (operation === 'save' && mode() === 'CLEAR' && !window.confirm(t('confirmKey'))) return;
    var form = payload(operation);
    busy = true; update(); status(operation === 'test' ? 'testing' : 'saving');
    request(operation === 'test' ? '/api/test' : '/api/save', form, function (code, body) {
      form.key = null;
      if (ended) return;
      busy = false;
      if (code >= 200 && code < 300 && body) {
        if (operation === 'test') { status(body.ok ? 'tested' : body.code === 'TEST_TRUNCATED' ? 'truncated' : 'testFailed', body.ok ? 'success' : 'error'); update(); return; }
        if (body.saved === true) {
          if (!body.state || body.state.valid !== true || !applyState(body.state)) { finish('unknown', 'error'); return; }
          finish(state.keyState === 'MEMORY_ONLY' ? 'savedMemory' : state.keyState === 'NONE' ? 'savedMissing' : 'saved', 'success'); return;
        }
      }
      if (code === 403 || code === 410) { finish('expired', 'error'); return; }
      if (body && body.state) {
        if (!applyState(body.state)) { finish('unknown', 'error'); return; }
        status(body.partial ? 'partial' : 'conflict', 'error'); update(); return;
      }
      if (!code || body && body.error === 'result_unknown') {
        if (operation === 'save') { finish('unknown', 'error'); return; }
        status('testFailed', 'error'); update(); return;
      }
      status(code === 400 || code === 415 || code === 413 ? 'badInput' : code === 409 || code === 429 ? 'busy' : operation === 'test' ? 'testFailed' : 'writeFailed', 'error');
      update();
    });
  }
  byId('connection-form').onsubmit = function (event) { event.preventDefault(); submit('save'); };
  byId('test').onclick = function () { submit('test'); };
  var editable = ['endpoint', 'model', 'replacement', 'tokens', 'thinking'];
  for (i = 0; i < editable.length; i++) { byId(editable[i]).oninput = edited; byId(editable[i]).onchange = edited; }
  var choices = document.querySelectorAll('input[name="key-edit"]');
  for (i = 0; i < choices.length; i++) choices[i].onchange = function () { if (mode() !== 'REPLACE') byId('replacement').value = ''; edited(); };
  byId('remove').onclick = function () {
    if (busy || ended || !state || !window.confirm(t('confirmClear'))) return;
    busy = true; update(); status('saving');
    request('/api/save', {version: 1, revision: state.revision, operation: 'clear'}, function (code, body) {
      if (ended) return;
      busy = false;
      if (code >= 200 && code < 300 && body && body.saved === true) {
        if (!body.state || body.state.valid !== true || body.state.keyState !== 'NONE' ||
            body.state.connection !== null || !applyState(body.state)) { finish('unknown', 'error'); return; }
        finish('cleared', 'success'); return;
      }
      if (code === 403 || code === 410) { finish('expired', 'error'); return; }
      if (!code || body && body.error === 'result_unknown') { finish('unknown', 'error'); return; }
      if (body && body.state) applyState(body.state);
      status('writeFailed', 'error'); update();
    });
  };
  byId('cancel').onclick = function () {
    if (busy && !window.confirm(t('cancelPending'))) return;
    if (!token) { finish('ended'); return; }
    ended = true; busy = false; byId('replacement').value = ''; update(); status('ending');
    request('/api/cancel', {}, function (code) { finish(code >= 200 && code < 300 || code === 403 || code === 410 ? 'ended' : 'endUnknown'); });
    token = '';
  };
  window.addEventListener('pagehide', function () { finish('expired'); });
  if (!/^[0-9a-f]{64}$/.test(token)) { finish('noToken', 'error'); return; }
  request('/api/state', {}, function (code, body) {
    if (code >= 200 && code < 300 && applyState(body)) status(body.valid ? 'ready' : 'invalidState', body.valid ? '' : 'error');
    else finish(code === 403 || code === 410 ? 'expired' : 'unreachable', 'error');
  });
}());
