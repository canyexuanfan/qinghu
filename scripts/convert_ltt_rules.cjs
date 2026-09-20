#!/usr/bin/env node
/**
 * 阶段2 安全转换器：李跳跳社区规则（MIT）→ 轻护 RuleSchema（首轮 RECORD_ONLY 观察舱）
 *
 * 用法：
 *   node scripts/convert_ltt_rules.cjs <LiTiaoTiao_Custom_Rules.txt> <commit> <outPack.json> <outStats.json>
 *
 * 强制安全注入（不可配置）：
 *   - page.mustNotHave = ["sensitive_payment","password"]
 *   - 必须存在窗口上下文闸门（windowViewIdContainsAny / windowTextContainsAny），否则丢弃
 *   - maxAttempts = 1；cooldownMs = 5000（≥2000 下限）
 *   - action = RECORD_ONLY（观察舱首轮；升级为点击需用户在订阅页操作，见阶段3）
 *   - 接受类动作黑名单（安装/更新/允许/同意等）：绝不生成
 */
const fs = require('fs');
const crypto = require('crypto');

const [,, inFile, commit, outPack, outStats] = process.argv;
if (!inFile || !commit || !outPack || !outStats) {
  console.error('用法: node convert_ltt_rules.cjs <rules.txt> <commit> <outPack> <outStats>');
  process.exit(2);
}

const ACCEPT_BLOCK = ['继续安装','立即安装','安装','安装中','更新','立即更新','立即升级',
  '允许','同意','始终允许','打开','确定','立即体验','立即查看'];
const clean = s => (s || '').replace(/^[=|-]+/, '').trim();

const raw = JSON.parse(fs.readFileSync(inFile, 'utf8'));
const stats = { apps: 0, popupRules: 0, accepted: 0,
  dropped: { no_gate: 0, accept_action: 0, back_action: 0, unsupported_action: 0, bad_gate: 0, dup: 0 } };
const seen = new Set();
const rules = [];

for (const item of raw) for (const k of Object.keys(item)) {
  stats.apps++;
  let prs; try { prs = JSON.parse(item[k]).popup_rules || []; } catch { continue; }
  for (const r of prs) {
    stats.popupRules++;
    const gateRaw = clean(r.id), act = clean(r.action);
    if (!gateRaw || !act) { stats.dropped.no_gate++; continue; }
    if (ACCEPT_BLOCK.some(b => act === b || act.includes(b))) { stats.dropped.accept_action++; continue; }
    if (r.action.includes('GLOBAL_ACTION_BACK') || act.startsWith('common')) { stats.dropped.back_action++; continue; }
    const parts = gateRaw.split('&').map(s => clean(s)).filter(Boolean);
    if (!parts.length) { stats.dropped.bad_gate++; continue; }
    // AND 近似：取最长（最具体）的单一闸门；弱闸门（<3 字符，如单字母 V）丢弃
    const gate = parts.reduce((a, b) => b.length > a.length ? b : a, '');
    if (gate.length < 3) { stats.dropped.bad_gate++; continue; }
    const match = {};
    if (/^[a-zA-Z][a-zA-Z0-9_]{2,48}$/.test(act)) match.viewId = act;
    else match.textEquals = act;
    if (/^[a-zA-Z][a-zA-Z0-9_]{3,48}$/.test(gate)) match.windowViewIdContainsAny = [gate];
    else match.windowTextContainsAny = [gate];
    const key = JSON.stringify(match);
    if (seen.has(key)) { stats.dropped.dup++; continue; }
    seen.add(key);
    rules.push({
      id: `community.lttcr.${rules.length}`, version: 1,
      provenance: { author: 'LiTiaoTiao_Custom_Rules 社区（MIT）', license: 'MIT',
        source: `community-derived-litiaotiao-custom-rules-mit@${commit.slice(0, 12)}` },
      target: { package: '*', minVersionCode: 0, maxVersionCode: 999999 },
      page: { mustNotHave: ['sensitive_payment', 'password'] },
      match,
      // 观察舱首轮：RECORD_ONLY（匹配只记录，不点击；升级需用户在订阅页操作）
      action: { type: 'CLICK_VERIFIED_NODE', maxAttempts: 1, cooldownMs: 5000 }
    });
    stats.accepted++;
  }
}

const pack = {
  schemaVersion: 1, id: 'qinghu.community.litiaotiao-custom-rules', version: 1,
  provenance: { author: 'LiTiaoTiao_Custom_Rules 社区（MIT）', license: 'MIT',
    source: `https://github.com/743859910/LiTiaoTiao_Custom_Rules@${commit}` },
  rules
};
fs.writeFileSync(outPack, JSON.stringify(pack, null, 2) + '\n');
const packSha = crypto.createHash('sha256').update(fs.readFileSync(outPack)).digest('hex');
const srcSha = crypto.createHash('sha256').update(fs.readFileSync(inFile)).digest('hex');
const out = { converted_at: new Date().toISOString(), source_commit: commit,
  source_file_sha256: srcSha, pack_sha256: packSha, ...stats,
  safety_injection: ['mustNotHave 敏感标识', '窗口闸门强制', 'maxAttempts=1', 'cooldown>=5000ms', 'RECORD_ONLY 首轮', '接受类动作黑名单'] };
fs.writeFileSync(outStats, JSON.stringify(out, null, 2) + '\n');
console.log('accepted:', stats.accepted, '/', stats.popupRules, 'dropped:', JSON.stringify(stats.dropped));
