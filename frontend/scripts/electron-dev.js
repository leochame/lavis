const http = require('http');
const { spawn } = require('child_process');

function checkViteRunning({ host = '127.0.0.1', port = 5173, timeoutMs = 600 }) {
  return new Promise((resolve) => {
    const req = http.request(
      {
        host,
        port,
        path: '/',
        method: 'GET',
        timeout: timeoutMs,
      },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => (data += chunk));
        res.on('end', () => {
          // 粗略判断是 Vite（HTML 里通常包含 /@vite 或 Vite 字样）
          const looksLikeVite =
            typeof data === 'string' &&
            (data.includes('/@vite') || data.toLowerCase().includes('vite'));
          resolve(res.statusCode === 200 && looksLikeVite);
        });
      },
    );

    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
    req.on('error', () => resolve(false));
    req.end();
  });
}

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { stdio: 'inherit', ...opts });
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${cmd} ${args.join(' ')} exited with code ${code}`));
    });
    child.on('error', reject);
  });
}

async function main() {
  // 标记主进程为开发模式，便于 main.ts 判断并加载 http://localhost:5173
  const devEnv = { ...process.env, NODE_ENV: 'development', ELECTRON_DEV: '1' };

  // 先编译 Electron 主进程代码
  await run('npm', ['run', 'build:electron'], {
    shell: process.platform === 'win32',
    env: devEnv,
  });

  const viteUp = await checkViteRunning({ port: 5173 });

  if (viteUp) {
    console.log('✅ 检测到 5173 上已有 Vite Dev Server，直接拉起 Electron（不再重复启动 Vite）...');
    // 直接启动 Electron（开发模式会 loadURL http://localhost:5173）
    await run('electron', ['.'], { shell: process.platform === 'win32', env: devEnv });
    return;
  }

  console.log('ℹ️ 未检测到 Vite Dev Server，启动 Vite + 等待就绪后拉起 Electron...');
  // 复用原来的并行启动逻辑
  await run(
    'npx',
    [
      'concurrently',
      '"npm run dev"',
      '"npx wait-on http://localhost:5173 && electron ."',
    ],
    { shell: true, env: devEnv },
  );
}

main().catch((err) => {
  console.error('❌ electron:dev failed:', err?.message || err);
  process.exit(1);
});


