(function () {
  'use strict';
  var zh = /^zh/i.test(navigator.language || '');
  var copy = {
    title: ['Subtitle connection', '电视字幕连接'],
    network: ['Use your own trusted home network. Local HTTP does not encrypt the connection details sent to your TV.', '仅在自家可信网络使用。局域网 HTTP 不加密手机发给电视的连接信息。'],
    loading: ['Reading the TV settings…', '正在读取电视设置…'],
    connection: ['Connection', '模型连接'], endpoint: ['HTTPS API endpoint', 'HTTPS API 接口地址'], model: ['Model ID', '模型 ID'],
    service: ['Platform / API protocol', '平台 / API 协议'],
    serviceCustom: ['Custom Chat Completions', '自定义 Chat Completions'],
    serviceOpenaiChat: ['OpenAI · Chat Completions', 'OpenAI · Chat Completions'],
    serviceOpenaiResponses: ['OpenAI · Responses', 'OpenAI · Responses'],
    serviceDeepseek: ['DeepSeek · Chat Completions', 'DeepSeek · Chat Completions'],
    serviceClaude: ['Claude · Messages', 'Claude · Messages'], serviceGemini: ['Gemini · native API', 'Gemini · 原生 API'],
    serviceHint: ['Changing platform resets thinking to automatic low. It fills only a blank or previous default endpoint and never chooses or replaces a model ID.', '切换平台会将思考策略重置为自动低档。仅在接口为空或仍为上个平台默认值时填入新地址，不会自动选择或替换模型 ID。'],
    geminiEndpoint: ['Gemini: use a models base URL (for example https://generativelanguage.googleapis.com/v1beta/models), or a full /models/MODEL:generateContent URL matching the model ID below.', 'Gemini：可填 models 基地址（例如 https://generativelanguage.googleapis.com/v1beta/models），或与下方模型 ID 一致的完整 /models/MODEL:generateContent 地址。'],
    workspace: ['Claude workspace ID (optional)', 'Claude workspace ID（可选）'],
    workspaceHint: ['Claude only: letters, digits, _ or -, up to 128 characters. Leave blank to omit the workspace header.', '仅用于 Claude：字母、数字、_ 或 -，最多 128 个字符。留空则不发送 workspace 请求头。'],
    key: ['Key', 'Key'], keep: ['Keep saved Key', '保留电视已存 Key'], replace: ['Use a new Key', '填写新 Key'], clearKey: ['Remove Key', '清除 Key'],
    newKey: ['New Key', '新 Key'], keyHint: ['The TV never returns your saved Key to this page.', '电视不会把已保存的 Key 返回到此页面。'],
    endpointChanged: ['Changing the endpoint, platform or workspace requires a new Key or explicitly removing the old one.', '更换接口地址、平台或 workspace 时，需要填写新 Key 或明确清除旧 Key。'],
    advanced: ['Output and model parameters', '输出与模型参数'], tokens: ['Maximum output tokens', '最大输出 token 数'], thinking: ['Thinking request policy', '思考请求策略'],
    thinkingAuto: ['Automatic: off or lowest supported', '自动：关闭或已支持的最低档'],
    thinkingDefault: ['Provider default (no extra parameter)', '服务默认（不额外发送参数）'], thinkingDisabled: ['Manual DeepSeek disabled (compatible models only)', '手动 DeepSeek disabled（仅兼容模型）'],
    thinkingNotice: ['Request parameters do not prove zero reasoning or cost. Automatic mode uses known model rules; unknown models keep provider defaults. Manual DeepSeek disabled is available only for Custom Chat and DeepSeek.', '请求参数不证明真实零思考或零费用。自动模式按已知模型规则处理，未知模型保留服务默认。手动 DeepSeek disabled 仅适用于自定义 Chat 和 DeepSeek。'],
    policyAuto: ['Automatic policy is evaluated on the TV when a request is built: known models use off, low effort or a 128-token budget; unknown models get no extra thinking control parameter. This draft has no model-specific preview.', '自动策略由电视构造请求时判断：已知模型使用关闭、低档或 128 token 预算；未知模型不额外添加思考控制参数。此草稿没有按模型预览的结果。'],
    policyDefault: ['No thinking control parameter will be added.', '不额外发送思考控制参数。'],
    policyDisabled: ['Request policy: ask the service to disable thinking.', '请求参数策略：要求服务关闭思考。'],
    policyLow: ['Request policy: ask the service for low thinking effort.', '请求参数策略：要求服务使用低档思考。'],
    policyBudget: ['Request policy: ask the service for a 128-token thinking budget.', '请求参数策略：要求服务使用 128 token 思考预算。'],
    policyUnknown: ['Unknown platform/model policy: automatic mode adds no thinking control parameter.', '平台 / 模型策略未知：自动模式不额外发送思考控制参数。'],
    modelHint: ['Limits and parameters must match your model. Saving alone does not verify model access.', '参数和上限须符合所选模型。保存配置不代表模型已验证可用。'],
    test: ['Test connection', '测试连接'], save: ['Save on TV', '保存到电视'],
    testHint: ['Testing sends one small request: usually up to 64 output tokens, or 256 when the known model needs a thinking budget. It does not save the form.', '测试会发送一次小请求：通常最多 64 个输出 token；已知模型需要思考预算时最多 256 个。不保存表单。'],
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
    badInput: ['Check the platform, HTTPS endpoint, model, token limit and optional Claude workspace. Key is not trimmed and cannot contain spaces or line breaks.', '请检查平台、HTTPS 地址、模型、token 上限和可选 Claude workspace。Key 不会自动裁剪，不能包含空格或换行。'],
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
  // Endpoint suggestions only. Model capabilities stay in the TV policy, never in this page.
  var endpoints = {
    CUSTOM_CHAT: '', OPENAI_CHAT: 'https://api.openai.com/v1/chat/completions',
    OPENAI_RESPONSES: 'https://api.openai.com/v1/responses', DEEPSEEK: 'https://api.deepseek.com/chat/completions',
    CLAUDE: 'https://api.anthropic.com/v1/messages', GEMINI: 'https://generativelanguage.googleapis.com/v1beta/models'
  };
  var policies = {PROVIDER_DEFAULT: 'policyDefault', UNKNOWN: 'policyUnknown', DISABLED: 'policyDisabled', LOW: 'policyLow', BUDGET_128: 'policyBudget'};
  var token = location.hash.substring(1), state = null, busy = false, ended = false;
  var selectedService = 'CUSTOM_CHAT', loadedPolicy = null;
  try { history.replaceState(null, '', '/'); } catch (ignored) { }
  function status(name, kind) {
    byId('status').textContent = t(name);
    byId('status').className = 'status' + (kind ? ' ' + kind : '');
  }
  function mode() { var chosen = document.querySelector('input[name="key-edit"]:checked'); return chosen ? chosen.value : 'REPLACE'; }
  function workspaceValue() { return byId('service').value === 'CLAUDE' ? byId('workspace').value.trim() || null : null; }
  function update() {
    var locked = busy || ended || !state, service = byId('service').value;
    byId('connection-fields').disabled = locked;
    byId('workspace-wrap').hidden = service !== 'CLAUDE';
    byId('workspace').disabled = locked || service !== 'CLAUDE';
    byId('gemini-endpoint-hint').hidden = service !== 'GEMINI';
    var manual = service === 'CUSTOM_CHAT' || service === 'DEEPSEEK';
    byId('thinking-deepseek').disabled = !manual;
    byId('thinking-deepseek').hidden = !manual;
    byId('thinking-policy').textContent = t(loadedPolicy || (byId('thinking').value === 'OMIT' ? 'policyDefault' :
      byId('thinking').value === 'DEEPSEEK_DISABLED' ? 'policyDisabled' : 'policyAuto'));
    byId('replacement-wrap').hidden = mode() !== 'REPLACE';
    byId('replacement').disabled = locked || mode() !== 'REPLACE';
    byId('replacement').required = mode() === 'REPLACE';
    byId('remove').disabled = locked;
    byId('test').disabled = locked || mode() === 'CLEAR' || mode() === 'KEEP' && state && state.keyState === 'NONE';
    var previous = state && state.connection;
    byId('endpoint-warning').hidden = !(previous && state.keyState !== 'NONE' && mode() === 'KEEP' &&
      (byId('endpoint').value.trim() !== previous.endpoint || service !== (previous.service || 'CUSTOM_CHAT') || workspaceValue() !== (previous.workspace || null)));
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
  function edited() { if (!busy && !ended && state) { loadedPolicy = null; status('changed'); update(); } }
  function applyState(next) {
    if (!next || next.version !== 1 || typeof next.revision !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(next.revision) ||
        typeof next.valid !== 'boolean' || ['NONE', 'PERSISTENT', 'MEMORY_ONLY'].indexOf(next.keyState) < 0) return false;
    var connection = next.connection;
    if (connection !== null && (!connection || typeof connection.endpoint !== 'string' ||
        typeof connection.model !== 'string' || typeof connection.tokens !== 'number' ||
        ['OMIT', 'DEEPSEEK_DISABLED', 'AUTO_LOW'].indexOf(connection.thinking) < 0)) return false;
    var service = connection && connection.service !== undefined ? connection.service : 'CUSTOM_CHAT';
    if (Object.keys(endpoints).indexOf(service) < 0 || connection &&
        (connection.workspace != null && (service !== 'CLAUDE' || typeof connection.workspace !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(connection.workspace)) ||
        connection.thinking === 'DEEPSEEK_DISABLED' && service !== 'CUSTOM_CHAT' && service !== 'DEEPSEEK' ||
        connection.thinkingPolicy !== undefined && Object.keys(policies).indexOf(connection.thinkingPolicy) < 0)) return false;
    state = next;
    selectedService = service;
    loadedPolicy = connection && policies[connection.thinkingPolicy] || null;
    byId('service').value = service;
    byId('endpoint').value = connection ? connection.endpoint : '';
    byId('model').value = connection ? connection.model : '';
    byId('workspace').value = connection && connection.workspace || '';
    byId('tokens').value = connection ? connection.tokens : 1024;
    byId('thinking').value = connection ? connection.thinking : 'AUTO_LOW';
    byId('replacement').value = '';
    var selected = next.keyState === 'NONE' ? 'REPLACE' : 'KEEP';
    document.querySelector('input[name="key-edit"][value="' + selected + '"]').checked = true;
    keyState(next.keyState);
    update();
    return true;
  }
  function payload(operation) {
    return {version: 1, revision: state.revision, operation: operation,
      service: byId('service').value, workspace: workspaceValue(),
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
  byId('service').onchange = function () {
    var next = byId('service').value;
    if (next === selectedService) return;
    var endpoint = byId('endpoint').value.trim();
    if (!endpoint || endpoint === endpoints[selectedService]) byId('endpoint').value = endpoints[next];
    selectedService = next;
    byId('thinking').value = 'AUTO_LOW';
    edited();
  };
  var editable = ['endpoint', 'model', 'workspace', 'replacement', 'tokens', 'thinking'];
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
