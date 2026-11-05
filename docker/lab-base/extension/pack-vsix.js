'use strict';

// 확장 폴더를 .vsix로 묶는다: node pack-vsix.js <확장 폴더> <출력 .vsix>
// vsix는 zip 파일이고 안에 [Content_Types].xml, extension.vsixmanifest, extension/<확장 파일>이 들어 있다.
// 기본 이미지 빌드에서 code-server에 들어 있는 node로 돌린다. vsce나 zip 도구를 받지 않으려고 직접 쓴다.
// 압축하지 않고(stored) 담는다. 확장이 수십 KB라 크기 차이가 없고, 읽는 쪽(yauzl)이 가장 단순하게 푼다.

const fs = require('fs');
const path = require('path');

// test/는 이미지에 넣지 않는다. 숨김 파일도 뺀다.
const EXCLUDED_DIRS = new Set(['test', 'node_modules']);

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function listFiles(root, rel = '') {
  const out = [];
  const entries = fs.readdirSync(path.join(root, rel), { withFileTypes: true })
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  for (const e of entries) {
    if (e.name.startsWith('.')) {
      continue;
    }
    const r = rel ? rel + '/' + e.name : e.name;
    if (e.isDirectory()) {
      if (!(rel === '' && EXCLUDED_DIRS.has(e.name))) {
        out.push(...listFiles(root, r));
      }
    } else if (e.isFile()) {
      out.push(r);
    }
  }
  return out;
}

function xml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function vsixManifest(pkg) {
  const kind = Array.isArray(pkg.extensionKind) ? pkg.extensionKind.join(',') : 'workspace';
  return `<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
  <Metadata>
    <Identity Language="en-US" Id="${xml(pkg.name)}" Version="${xml(pkg.version)}" Publisher="${xml(pkg.publisher)}" />
    <DisplayName>${xml(pkg.displayName || pkg.name)}</DisplayName>
    <Description xml:space="preserve">${xml(pkg.description)}</Description>
    <Tags></Tags>
    <Categories>${xml((pkg.categories || []).join(','))}</Categories>
    <GalleryFlags>Public</GalleryFlags>
    <Properties>
      <Property Id="Microsoft.VisualStudio.Code.Engine" Value="${xml(pkg.engines && pkg.engines.vscode)}" />
      <Property Id="Microsoft.VisualStudio.Code.ExtensionDependencies" Value="" />
      <Property Id="Microsoft.VisualStudio.Code.ExtensionPack" Value="" />
      <Property Id="Microsoft.VisualStudio.Code.ExtensionKind" Value="${xml(kind)}" />
    </Properties>
  </Metadata>
  <Installation>
    <InstallationTarget Id="Microsoft.VisualStudio.Code"/>
  </Installation>
  <Dependencies/>
  <Assets>
    <Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" />
  </Assets>
</PackageManifest>
`;
}

function contentTypes(names) {
  const types = {
    '.json': 'application/json',
    '.js': 'application/javascript',
    '.svg': 'image/svg+xml',
    '.vsixmanifest': 'text/xml',
    '.md': 'text/markdown',
  };
  const exts = [...new Set(names.map((n) => path.extname(n).toLowerCase()).filter(Boolean))].sort();
  const lines = exts.map((e) => `<Default Extension="${xml(e)}" ContentType="${xml(types[e] || 'application/octet-stream')}"/>`);
  return '<?xml version="1.0" encoding="utf-8"?>\n'
    + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' + lines.join('') + '</Types>\n';
}

// 고정된 시각(1980-01-01 00:00)을 넣어 같은 소스면 같은 파일이 나오게 한다.
const DOS_TIME = 0;
const DOS_DATE = (0 << 9) | (1 << 5) | 1;
const UNIX_FILE_MODE = 0o100644;

function zip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const crc = crc32(data);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // 풀 때 필요한 버전 2.0
    local.writeUInt16LE(0x0800, 6); // 이름이 UTF-8
    local.writeUInt16LE(0, 8); // stored
    local.writeUInt16LE(DOS_TIME, 10);
    local.writeUInt16LE(DOS_DATE, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, nameBuf, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE((3 << 8) | 20, 4); // 만든 곳 UNIX, 버전 2.0
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(DOS_TIME, 12);
    central.writeUInt16LE(DOS_DATE, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt16LE(0, 30); // extra
    central.writeUInt16LE(0, 32); // comment
    central.writeUInt16LE(0, 34); // disk
    central.writeUInt16LE(0, 36); // internal attrs
    central.writeUInt32LE((UNIX_FILE_MODE << 16) >>> 0, 38);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);

    offset += local.length + nameBuf.length + data.length;
  }
  const centralBuf = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...locals, centralBuf, end]);
}

function pack(extDir, outFile) {
  const pkg = JSON.parse(fs.readFileSync(path.join(extDir, 'package.json'), 'utf8'));
  for (const k of ['name', 'publisher', 'version', 'main']) {
    if (!pkg[k]) {
      throw new Error('package.json에 ' + k + '가 없습니다');
    }
  }
  const files = listFiles(extDir);
  if (!files.includes(path.posix.normalize(pkg.main.replace(/^\.\//, '')))) {
    throw new Error('main 파일이 없습니다: ' + pkg.main);
  }
  const entries = [
    { name: '[Content_Types].xml', data: Buffer.from(contentTypes(['x.vsixmanifest', ...files]), 'utf8') },
    { name: 'extension.vsixmanifest', data: Buffer.from(vsixManifest(pkg), 'utf8') },
    ...files.map((f) => ({ name: 'extension/' + f, data: fs.readFileSync(path.join(extDir, f)) })),
  ];
  fs.writeFileSync(outFile, zip(entries));
  return entries.map((e) => e.name);
}

if (require.main === module) {
  const [extDir, outFile] = process.argv.slice(2);
  if (!extDir || !outFile) {
    console.error('사용법: node pack-vsix.js <확장 폴더> <출력 .vsix>');
    process.exit(2);
  }
  const names = pack(path.resolve(extDir), path.resolve(outFile));
  console.log('vsix: ' + outFile + ' (' + names.length + ' entries)');
  for (const n of names) {
    console.log('  ' + n);
  }
}

module.exports = { pack, crc32 };
