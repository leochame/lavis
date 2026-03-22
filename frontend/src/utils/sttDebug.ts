/**
 * STT 调试工具
 *
 * 在浏览器控制台中使用：
 * 1. 打开开发者工具 (F12)
 * 2. 在 Console 中输入: window.sttDebug.enable()
 * 3. 尝试录音
 * 4. 查看详细的调试信息
 *
 * NOTE: This module is only active in DEV mode.
 */

const MAX_LOG_SIZE = 1000;

class SttDebugger {
  private enabled = false;
  private logs: Array<{ time: string; type: string; message: string; data?: any }> = [];

  enable() {
    this.enabled = true;
    console.log('%c[STT Debug] 调试模式已启用', 'color: green; font-weight: bold');
    console.log('现在尝试录音，所有 STT 相关的操作都会被记录');
  }

  disable() {
    this.enabled = false;
    console.log('%c[STT Debug] 调试模式已禁用', 'color: gray');
  }

  log(type: string, message: string, data?: any) {
    const time = new Date().toISOString();
    const logEntry = { time, type, message, data };

    // Enforce max log size to prevent memory leaks
    if (this.logs.length >= MAX_LOG_SIZE) {
      this.logs = this.logs.slice(this.logs.length - MAX_LOG_SIZE + 1);
    }
    this.logs.push(logEntry);

    if (this.enabled) {
      const color = type === 'error' ? 'red' : type === 'warn' ? 'orange' : 'blue';
      console.log(`%c[STT Debug ${type}] ${message}`, `color: ${color}`);
      if (data) {
        console.log('  Data:', data);
      }
    }
  }

  getLogs() {
    return this.logs;
  }

  clearLogs() {
    this.logs = [];
    console.log('[STT Debug] 日志已清空');
  }

  exportLogs() {
    const json = JSON.stringify(this.logs, null, 2);
    console.log('[STT Debug] 导出日志:');
    console.log(json);
    return json;
  }

  // 测试 STT 端点
  async testEndpoint() {
    console.log('%c[STT Debug] 测试 STT 端点...', 'color: blue; font-weight: bold');

    try {
      // 创建一个小的测试音频 Blob
      const testBlob = new Blob([new Uint8Array(1024)], { type: 'audio/webm' });
      const testFile = new File([testBlob], 'test.webm', { type: 'audio/webm' });

      console.log('[STT Debug] 创建测试文件:', {
        size: testFile.size,
        type: testFile.type,
        name: testFile.name
      });

      const formData = new FormData();
      formData.append('file', testFile);
      formData.append('use_orchestrator', 'false');

      const endpoint = `${window.location.origin}/api/agent/voice-chat`;
      console.log(`[STT Debug] 发送请求到 ${endpoint}...`);
      const startTime = Date.now();

      const response = await fetch(endpoint, {
        method: 'POST',
        body: formData,
      });

      const duration = Date.now() - startTime;
      console.log(`[STT Debug] 响应收到 (耗时: ${duration}ms):`, {
        status: response.status,
        statusText: response.statusText,
        headers: Object.fromEntries(response.headers.entries())
      });

      if (response.ok) {
        const data = await response.json();
        console.log('%c[STT Debug] ✅ 测试成功！', 'color: green; font-weight: bold');
        console.log('响应数据:', data);
        return { success: true, data };
      } else {
        const errorText = await response.text();
        console.log('%c[STT Debug] ❌ 测试失败', 'color: red; font-weight: bold');
        console.log('错误响应:', errorText);
        return { success: false, error: errorText };
      }
    } catch (error) {
      console.log('%c[STT Debug] ❌ 测试出错', 'color: red; font-weight: bold');
      console.error('错误:', error);
      return { success: false, error };
    }
  }

  // 检查配置
  checkConfig() {
    console.log('%c[STT Debug] 检查配置...', 'color: blue; font-weight: bold');

    const checks = {
      backend: window.location.origin,
      frontend: window.location.origin,
      userAgent: navigator.userAgent,
      mediaDevices: !!navigator.mediaDevices,
      getUserMedia: !!navigator.mediaDevices?.getUserMedia,
    };

    console.table(checks);
    return checks;
  }
}

// 创建全局实例（仅在 DEV 模式下）
let sttDebug: SttDebugger | undefined;

if (import.meta.env.DEV) {
  sttDebug = new SttDebugger();

  // 暴露到 window 对象
  if (typeof window !== 'undefined') {
    (window as any).sttDebug = sttDebug;
  }
}

export default sttDebug;
