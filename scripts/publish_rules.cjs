#!/usr/bin/env node
/**
 * 阶段4：规则包发布脚本（打包 + 签名 + 版本清单）。
 *
 * 用法：
 *   node scripts/publish_rules.cjs --pack <pack.json> --out <outdir> [--key <pem路径>] [--name <包文件名>] [--dry-run]
 *
 * 硬性要求（ADR-006 落实）：
 *   - 签名私钥绝不写入仓库、绝不落服务器：路径经 --key 或环境变量 RULE_SIGN_KEY 注入；
 *     脚本显式检查私钥文件不在仓库目录内，违者拒绝执行。
 *   - 未提供 --key 时仅允许 --dry-run（只算哈希与清单，不产生 .sig）。
 *
 * 产物：
 *   <out>/<name>            规则包（原样字节）
 *   <out>/<name>.sig        ECDSA-P256-SHA256 签名（DER，与 RuleSignatureVerifier 兼容）
 *   <out>/version.json      {id, version, ruleCount, sha256, keyId, signedAt}
 *   <out>/manifest.json     发布清单（哈希/时间/keyId/大小）
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const args = process.argv.slice(2);
function arg(name) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : null;
}
const has = name => args.includes(name);
const packPath = arg('--pack');
const outDir = arg('--out') || 'rules-dist';
const keyPath = arg('--key') || process.env.RULE_SIGN_KEY || null;
const name = arg('--name') || (packPath ? path.basename(packPath) : null);
const dryRun = has('--dry-run');

if (!packPath || !name) { console.error('需要 --pack <pack.json> 与 [--name <文件名>]'); process.exit(2); }
if (!fs.existsSync(packPath)) { console.error('规则包不存在：' + packPath); process.exit(2); }

// 私钥安全检查：私钥绝不能在仓库目录内
if (keyPath) {
  const kp = path.resolve(keyPath);
  const repoRoot = path.resolve(__dirname, '..');
  if (kp.startsWith(repoRoot)) {
    console.error('拒绝执行：私钥位于仓库目录内（' + kp + '）。私钥必须离线受控存放。');
    process.exit(2);
  }
  if (!fs.existsSync(kp)) { console.error('私钥文件不存在：' + kp); process.exit(2); }
} else if (!dryRun) {
  console.error('未提供 --key：正式签名需要私钥；如仅生成清单请加 --dry-run。');
  process.exit(2);
}

const packBytes = fs.readFileSync(packPath);
const sha256 = crypto.createHash('sha256').update(packBytes).digest('hex');
const packJson = JSON.parse(packBytes.toString('utf8'));

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, name), packBytes);

// 版本清单（客户端先拉这个小文件，内容未变不下载主包）
const keyId = keyPath ? 'key-' + crypto.createHash('sha256').update(keyPath).digest('hex').slice(0, 8) : 'unsigned';
const version = { id: packJson.id || name, version: packJson.version || 1,
  ruleCount: (packJson.rules || []).length, sha256, keyId,
  signedAt: new Date().toISOString() };
fs.writeFileSync(path.join(outDir, 'version.json'), JSON.stringify(version, null, 2) + '\n');

// 签名（ECDSA P-256 + SHA256，DER 编码）
let sigOk = false;
if (keyPath && !dryRun) {
  const pem = fs.readFileSync(keyPath, 'utf8');
  const sign = crypto.createSign('SHA256');
  sign.update(packBytes);
  const sig = sign.sign(pem);
  fs.writeFileSync(path.join(outDir, name + '.sig'), sig);
  // 自校验（发布前确认签名与包匹配）
  const verify = crypto.createVerify('SHA256');
  verify.update(packBytes);
  sigOk = verify.verify(pem, sig);
  if (!sigOk) { console.error('签名自校验失败'); process.exit(2); }
} else {
  console.log('--dry-run / 无私钥：跳过 .sig 生成');
}

// 发布清单
const manifest = { pack: name, sha256, bytes: packBytes.length,
  keyId: keyPath ? keyId : 'none', signed: keyPath ? sigOk || dryRun : false,
  generatedAt: new Date().toISOString(), version };
fs.writeFileSync(path.join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');

console.log('=== publish_rules ' + (dryRun ? '(dry-run)' : '') + ' ===');
console.log('pack      :', name, packBytes.length, 'bytes');
console.log('sha256    :', sha256);
console.log('version   :', packJson.version, '| rules:', (packJson.rules || []).length);
console.log('keyId     :', keyId, '| signed:', keyPath ? 'yes' : 'no (dry-run/无私钥)');
console.log('产物目录  :', path.resolve(outDir));
