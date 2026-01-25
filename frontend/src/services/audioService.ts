/**
 * 单例 Audio 服务
 * 全局维护唯一的 Audio 实例，避免频繁创建 DOM 节点
 * 实现"消费即焚"策略：播放完成后立即清理 URL
 */
class AudioService {
  private audioInstance: HTMLAudioElement | null = null;
  private currentUrl: string | null = null;
  private isPlaying = false;
  private onEndedCallbacks: Set<() => void> = new Set();

  /**
   * 获取或创建单例 Audio 实例
   */
  private getAudio(): HTMLAudioElement {
    if (!this.audioInstance) {
      this.audioInstance = new Audio();
      // 监听播放结束，自动清理并触发回调
      this.audioInstance.addEventListener('ended', () => {
        this.cleanup();
        // 触发所有注册的回调
        this.onEndedCallbacks.forEach(cb => cb());
        this.onEndedCallbacks.clear();
      });
      this.audioInstance.addEventListener('error', () => {
        console.error('[AudioService] Audio playback error');
        this.cleanup();
        // 触发所有注册的回调
        this.onEndedCallbacks.forEach(cb => cb());
        this.onEndedCallbacks.clear();
      });
    }
    return this.audioInstance;
  }

  /**
   * 播放音频
   * @param audioUrl Blob URL
   * @param onEnded 播放结束回调（可选）
   * @returns Promise<void>
   */
  async play(audioUrl: string, onEnded?: () => void): Promise<void> {
    // 如果正在播放，先停止并清理
    if (this.isPlaying) {
      this.stop();
    }

    // 注册播放结束回调
    if (onEnded) {
      this.onEndedCallbacks.add(onEnded);
    }

    const audio = this.getAudio();
    this.currentUrl = audioUrl;
    this.isPlaying = true;
    audio.src = audioUrl;

    try {
      await audio.play();
    } catch (error) {
      console.error('[AudioService] Failed to play audio:', error);
      this.cleanup();
      // 触发所有注册的回调
      this.onEndedCallbacks.forEach(cb => cb());
      this.onEndedCallbacks.clear();
      throw error;
    }
  }

  /**
   * 停止播放
   */
  stop(): void {
    if (this.audioInstance) {
      this.audioInstance.pause();
      this.audioInstance.currentTime = 0;
    }
    this.cleanup();
    // 清空所有回调
    this.onEndedCallbacks.clear();
  }

  /**
   * 清理资源
   */
  private cleanup(): void {
    if (this.currentUrl) {
      URL.revokeObjectURL(this.currentUrl);
      this.currentUrl = null;
    }
    this.isPlaying = false;
  }

  /**
   * 检查是否正在播放
   */
  getIsPlaying(): boolean {
    return this.isPlaying && this.audioInstance !== null && !this.audioInstance.paused;
  }

  /**
   * 销毁服务（清理所有资源）
   */
  destroy(): void {
    this.stop();
    if (this.audioInstance) {
      this.audioInstance = null;
    }
  }
}

// 导出单例
export const audioService = new AudioService();

