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
  const choices = ['KEEP', 'REPLACE', 'CLEAR'].map(value => ({value, checked: value === 'REPLACE'}));
  const location = {host: '192.168.1.2:12345', hash: '#' + (input.token === '' ? '' : 'f'.repeat(64))};
  const requests = [];
  function Xhr() {}
  Xhr.prototype.open = function(method, path) { this.method = method; this.path = path; this.headers = {}; };
  Xhr.prototype.setRequestHeader = function(name, value) { this.headers[name] = value; };
  Xhr.prototype.send = function(body) {
    requests.push({method: this.method, path: this.path, payload: JSON.parse(body),
      authorizationValid: this.headers.Authorization === 'Pair ' + 'f'.repeat(64)});
    if (input.event === 'error') return this.onerror();
    if (input.event === 'timeout') return this.ontimeout();
    this.status = input.status;
    this.responseText = input.text === undefined ? JSON.stringify(input.body) : input.text;
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
    XMLHttpRequest: Xhr, addEventListener() {}, confirm() { return false; }
  };
  context.window = context;
  vm.runInNewContext(source, context, {timeout: 1000});
  return {status: node('status').textContent, formDisabled: node('connection-fields').disabled,
    replacementEmpty: node('replacement').value === '', fragmentRemoved: location.hash === '', requests};
}
console.log(JSON.stringify(Object.fromEntries(inputs.map(input => [input.name, execute(input)]))));
"""


class PairingPageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        node = shutil.which("node")
        if not node:
            raise RuntimeError("Node.js 20+ is required for production pairing-page regression fixtures")
        result = subprocess.run([node, "-e", FIXTURE, str(SCRIPT)], capture_output=True,
                                text=True, timeout=15, check=True)
        cls.cases = json.loads(result.stdout)

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
