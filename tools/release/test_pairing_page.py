"""Execute the production pairing page with in-memory DOM/XHR seams; no browser or network."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "common/src/main/assets/caption-pairing/pair.js"
FIXTURE = r"""
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[1], 'utf8');
const valid = {version: 1, revision: '0', valid: true, keyState: 'NONE', connection: null};
const inputs = [
  {name: 'ready', status: 200, body: valid},
  {name: 'readFailed', status: 503, body: {ok: false, code: 'READ_FAILED'}},
  {name: 'readFailedEnglish', status: 503, body: {ok: false, code: 'READ_FAILED'}, language: 'en'},
  {name: 'badState', status: 200, body: {...valid, revision: 0}},
  {name: 'badJson', status: 200, text: '{incomplete'},
  {name: 'forbidden', status: 403, body: {}},
  {name: 'expired', status: 410, body: {}},
  {name: 'networkError', event: 'error'},
  {name: 'networkTimeout', event: 'timeout'},
  {name: 'incompleteState', status: 200, body: {...valid, valid: false}},
  {name: 'noToken', status: 200, body: valid, token: ''}
];
function execute(input) {
  const nodes = Object.create(null);
  function node(id) {
    if (!nodes[id]) nodes[id] = {value: '', checked: false, disabled: false,
      hidden: false, required: false, textContent: '', className: ''};
    return nodes[id];
  }
  const choices = ['KEEP', 'REPLACE', 'CLEAR'].map(value => {
    let checked = value === 'REPLACE';
    return {value, get checked() { return checked; }, set checked(next) {
      if (next) for (const other of choices) if (other.value !== value) other.checked = false;
      checked = next;
    }};
  });
  const replies = input.replies ? input.replies.slice() : [input];
  const location = {host: '192.168.1.2:12345', hash: '#' + (input.token === '' ? '' : 'f'.repeat(64))};
  const requests = [];
  function Xhr() {}
  Xhr.prototype.open = function(method, path) { this.method = method; this.path = path; this.headers = {}; };
  Xhr.prototype.setRequestHeader = function(name, value) { this.headers[name] = value; };
  Xhr.prototype.send = function(body) {
    requests.push({method: this.method, path: this.path, payload: JSON.parse(body),
      authorizationValid: this.headers.Authorization === 'Pair ' + 'f'.repeat(64)});
    const reply = replies.shift();
    if (!reply) throw new Error('Unexpected request');
    if (reply.event === 'error') return this.onerror();
    if (reply.event === 'timeout') return this.ontimeout();
    this.status = reply.status;
    this.responseText = reply.text === undefined ? JSON.stringify(reply.body) : reply.text;
    this.onload();
  };
  const context = {
    navigator: {language: input.language || 'zh-CN'}, location,
    history: {replaceState() { location.hash = ''; }},
    document: {documentElement: {}, title: '', getElementById: node,
      querySelectorAll(selector) { return selector === 'input[name="key-edit"]' ? choices : []; },
      querySelector(selector) {
        if (selector === 'input[name="key-edit"]:checked') return choices.find(c => c.checked) || null;
        const match = /\[value="([^"]+)"\]/.exec(selector);
        if (!match) throw new Error('Unexpected selector');
        return choices.find(c => c.value === match[1]) || null;
      }},
    XMLHttpRequest: Xhr, addEventListener() {}, confirm() { return Boolean(input.confirm); }
  };
  context.window = context;
  vm.runInNewContext(source, context, {timeout: 1000});
  if (input.operation) {
    for (const [id, value] of Object.entries(input.draft)) node(id).value = value;
    node('connection-form').reportValidity = () => true;
    if (input.operation === 'test') node('test').onclick();
    else if (input.operation === 'clear') node('remove').onclick();
    else node('connection-form').onsubmit({preventDefault() {}});
    if (input.retryEndpoint) {
      node('endpoint').value = input.retryEndpoint;
      node('endpoint').oninput();
      node('connection-form').onsubmit({preventDefault() {}});
    }
  }
  return {status: node('status').textContent, formDisabled: node('connection-fields').disabled,
    replacementEmpty: node('replacement').value === '', fragmentRemoved: location.hash === '', requests,
    fields: Object.fromEntries(['service', 'endpoint', 'model', 'workspace', 'tokens', 'thinking', 'replacement']
      .map(id => [id, node(id).value])), keyEdit: choices.find(c => c.checked).value};
}
const draft = {service: 'DEEPSEEK', endpoint: 'http://fixture.invalid/chat', model: 'fixture-model',
  workspace: '', tokens: 512, thinking: 'OMIT', replacement: 'synthetic-retry-key-not-a-credential'};
const changed = {...valid, revision: '1', keyState: 'PERSISTENT', connection: {
  service: 'CUSTOM_CHAT', endpoint: 'https://saved.invalid/chat', model: 'saved-model', tokens: 1024, thinking: 'AUTO_LOW'
}};
const recovery = [
  {name: 'invalidInput', status: 400, body: {ok: false, code: 'INVALID_INPUT', state: valid}},
  {name: 'writeFailed', status: 503, body: {ok: false, code: 'SAVE_FAILED', state: valid}},
  {name: 'clearWriteFailed', operation: 'clear', confirm: true, status: 503, body: {ok: false, code: 'SAVE_FAILED', state: valid}},
  {name: 'testInvalidInput', operation: 'test', status: 400, body: {ok: false, code: 'INVALID_INPUT', state: valid}},
  {name: 'conflict', status: 409, body: {ok: false, code: 'REVISION_CONFLICT', state: changed}},
  {name: 'clearConflict', operation: 'clear', confirm: true, status: 409, body: {ok: false, code: 'REVISION_CONFLICT', state: changed}},
  {name: 'sameRevisionConflict', status: 409, body: {ok: false, code: 'REVISION_CONFLICT', state: valid}},
  {name: 'partial', status: 503, body: {ok: false, partial: true, code: 'PARTIAL', state: changed}},
  {name: 'sameRevisionPartial', status: 503, body: {ok: false, partial: true, code: 'PARTIAL', state: valid}},
  {name: 'changedRevision', status: 503, body: {ok: false, code: 'SAVE_FAILED', state: changed}},
  {name: 'unknown', event: 'timeout'},
  {name: 'unknownWithState', status: 503, body: {error: 'result_unknown', state: valid}},
  {name: 'malformedState', status: 503, body: {ok: false, code: 'SAVE_FAILED', state: {...valid, keyState: 'INVALID'}}},
  {name: 'correctAndRetry', status: 400, body: {ok: false, code: 'INVALID_INPUT', state: valid},
    retryEndpoint: 'https://fixture.invalid/chat'}
].map(input => [input.name, execute({...input, draft, operation: input.operation || 'save',
  replies: [{status: 200, body: valid}, input, {status: 200, body: {saved: true, state: changed}}]})]);
console.log(JSON.stringify({initial: Object.fromEntries(inputs.map(input => [input.name, execute(input)])),
  recovery: Object.fromEntries(recovery)}));
"""


class PairingPageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        node = shutil.which("node")
        if not node:
            raise RuntimeError("Node.js 20+ is required for production pairing-page regression fixtures")
        result = subprocess.run([node, "-e", FIXTURE, str(SCRIPT)], capture_output=True,
                                text=True, timeout=15, check=True)
        data = json.loads(result.stdout)
        cls.cases = data["initial"]
        cls.recovery = data["recovery"]

    def test_rejected_input_preserves_the_editable_draft_for_correction(self):
        for name in ("invalidInput", "testInvalidInput", "writeFailed", "clearWriteFailed"):
            with self.subTest(name=name):
                case = self.recovery[name]
                self.assertFalse(case["formDisabled"])
                self.assertEqual(case["fields"], {
                    "service": "DEEPSEEK", "endpoint": "http://fixture.invalid/chat",
                    "model": "fixture-model", "workspace": "", "tokens": 512,
                    "thinking": "OMIT", "replacement": "synthetic-retry-key-not-a-credential"})
                self.assertEqual(case["keyEdit"], "REPLACE")
                self.assertNotIn("电视设置已变更", case["status"])
                self.assertIn("请检查" if name not in ("writeFailed", "clearWriteFailed") else "保存未完成", case["status"])

    def test_real_conflict_or_changed_revision_reloads_and_wipes_the_typed_key(self):
        for name in ("conflict", "changedRevision", "clearConflict"):
            with self.subTest(name=name):
                case = self.recovery[name]
                self.assertIn("电视设置已变更", case["status"])
                self.assertEqual(case["fields"]["endpoint"], "https://saved.invalid/chat")
                self.assertEqual(case["fields"]["model"], "saved-model")
                self.assertTrue(case["replacementEmpty"])
                self.assertEqual(case["keyEdit"], "KEEP")
                self.assertFalse(case["formDisabled"])

    def test_explicit_conflict_or_partial_result_still_reloads_when_revision_matches(self):
        for name, text in (("sameRevisionConflict", "电视设置已变更"),
                           ("sameRevisionPartial", "连接已有部分变更")):
            with self.subTest(name=name):
                case = self.recovery[name]
                self.assertIn(text, case["status"])
                self.assertEqual(case["fields"]["endpoint"], "")
                self.assertTrue(case["replacementEmpty"])
                self.assertFalse(case["formDisabled"])

    def test_partial_save_reloads_the_actual_state_without_reporting_success(self):
        case = self.recovery["partial"]
        self.assertIn("连接已有部分变更", case["status"])
        self.assertEqual(case["fields"]["endpoint"], "https://saved.invalid/chat")
        self.assertTrue(case["replacementEmpty"])
        self.assertFalse(case["formDisabled"])

    def test_unknown_save_outcome_remains_locked_even_if_a_state_is_attached(self):
        for name in ("unknown", "unknownWithState", "malformedState"):
            with self.subTest(name=name):
                case = self.recovery[name]
                self.assertIn("未收到保存确认", case["status"])
                self.assertTrue(case["formDisabled"])
                self.assertTrue(case["replacementEmpty"])

    def test_corrected_retry_sends_the_preserved_key_once_and_ends_on_ack(self):
        case = self.recovery["correctAndRetry"]
        self.assertEqual(len(case["requests"]), 3)
        retry = case["requests"][2]
        self.assertEqual(retry["path"], "/api/save")
        self.assertEqual(retry["payload"]["revision"], "0")
        self.assertEqual(retry["payload"]["endpoint"], "https://fixture.invalid/chat")
        self.assertEqual(retry["payload"]["key"], "synthetic-retry-key-not-a-credential")
        self.assertTrue(all(request["authorizationValid"] for request in case["requests"]))
        self.assertIn("已保存到电视", case["status"])
        self.assertTrue(case["formDisabled"])
        self.assertTrue(case["replacementEmpty"])

    def test_valid_empty_state_opens_the_form(self):
        case = self.cases["ready"]
        self.assertFalse(case["formDisabled"])
        self.assertIn("核对内容", case["status"])

    def test_http_read_failure_is_not_reported_as_network_failure(self):
        self.assertIn("已连接到电视", self.cases["readFailed"]["status"])
        self.assertIn("读取配置失败", self.cases["readFailed"]["status"])
        self.assertTrue(self.cases["readFailed"]["formDisabled"])

    def test_http_read_failure_is_distinct_in_english(self):
        status = self.cases["readFailedEnglish"]["status"]
        self.assertIn("Connected to the TV", status)
        self.assertNotIn("Cannot reach", status)

    def test_malformed_state_is_reported_as_invalid_response(self):
        self.assertIn("配置数据无效", self.cases["badState"]["status"])
        self.assertTrue(self.cases["badState"]["formDisabled"])

    def test_non_json_reply_is_reported_as_invalid_response(self):
        self.assertIn("配置数据无效", self.cases["badJson"]["status"])
        self.assertTrue(self.cases["badJson"]["formDisabled"])

    def test_forbidden_and_expired_sessions_stay_expired(self):
        for name in ("forbidden", "expired"):
            with self.subTest(name=name):
                self.assertIn("配对已结束或已失效", self.cases[name]["status"])
                self.assertTrue(self.cases[name]["formDisabled"])

    def test_missing_network_responses_stay_network_errors(self):
        for name in ("networkError", "networkTimeout"):
            with self.subTest(name=name):
                self.assertIn("无法连接电视", self.cases[name]["status"])
                self.assertTrue(self.cases[name]["formDisabled"])

    def test_recoverable_invalid_store_stays_editable(self):
        self.assertIn("电视未能完整读取设置", self.cases["incompleteState"]["status"])
        self.assertFalse(self.cases["incompleteState"]["formDisabled"])

    def test_initial_read_keeps_auth_and_fragment_privacy(self):
        for name, case in self.cases.items():
            with self.subTest(name=name):
                self.assertTrue(case["replacementEmpty"])
                self.assertTrue(case["fragmentRemoved"])
                if name == "noToken":
                    self.assertEqual(case["requests"], [])
                    self.assertIn("请扫描电视显示的二维码", case["status"])
                else:
                    self.assertEqual(len(case["requests"]), 1)
                    request = case["requests"][0]
                    self.assertEqual((request["method"], request["path"], request["payload"]),
                                     ("POST", "/api/state", {}))
                    self.assertTrue(request["authorizationValid"])

    def test_fork_display_names_keep_capital_t(self):
        for flavor, label in (("stbeta", "T-SmartTube beta"), ("ststable", "T-SmartTube")):
            with self.subTest(flavor=flavor):
                resource = ROOT / "smarttubetv/src" / flavor / "res/values/strings.xml"
                name = ET.parse(resource).getroot().find("string[@name='app_name']")
                self.assertEqual(name.text, label)
