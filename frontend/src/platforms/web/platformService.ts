import type { IPlatformService, SnapState } from '../interface';

/**
 * Web 平台实现：大部分原生能力为空操作或使用 Web API 近似实现。
 */
export class WebPlatformService implements IPlatformService {
  resizeWindow(): void {
    // Web 环境无法控制物理窗口，保持空实现
  }

  minimizeWindow(): void {
    // Web 环境不支持最小化
  }

  hideWindow(): void {
    // Web 环境不支持隐藏窗口
  }

  setAlwaysOnTop(): void {
    // Web 环境不支持置顶
  }

  setIgnoreMouseEvents(): void {
    // Web 环境不支持鼠标穿透
  }

  async getSnapState(): Promise<SnapState> {
    // Web 环境不支持边缘吸附
    return { isSnapped: false, position: null };
  }

  async getScreenshot(): Promise<string | null> {
    // 使用 getDisplayMedia 近似实现，需用户授权
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: true,
        audio: false,
      } as DisplayMediaStreamOptions);
      const track = stream.getVideoTracks()[0];

      // ImageCapture 在部分类型定义中缺失，使用 any 兼容
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const ImageCaptureCtor = (window as any).ImageCapture;
      if (!ImageCaptureCtor) {
        track.stop();
        return null;
      }
      const imageCapture = new ImageCaptureCtor(track);
      const bitmap = await imageCapture.grabFrame();

      const canvas = document.createElement('canvas');
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        track.stop();
        return null;
      }
      ctx.drawImage(bitmap, 0, 0);
      const dataUrl = canvas.toDataURL('image/png');
      track.stop();
      return dataUrl;
    } catch (error) {
      console.warn('[WebPlatformService] Screenshot failed:', error);
      return null;
    }
  }

  openExternalUrl(url: string): void {
    window.open(url, '_blank', 'noopener,noreferrer');
  }

  async checkMicrophonePermission(): Promise<boolean> {
    try {
      const permission = await navigator.permissions.query({ name: 'microphone' as PermissionName });
      if (permission.state === 'granted') return true;
      if (permission.state === 'prompt') {
        // 触发一次请求以提示用户
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        stream.getTracks().forEach((t) => t.stop());
        return true;
      }
      return false;
    } catch (error) {
      console.warn('[WebPlatformService] Mic permission check failed:', error);
      return false;
    }
  }
}

