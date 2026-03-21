package com.lavis.feature.scheduler;

import lombok.Data;

@Data
public class TaskRequest {
    private String name;
    private String description;
    private String cronExpression;
    /**
     * 调度模式:
     * - CRON: 使用 cronExpression
     * - LOOP: 使用 intervalSeconds 固定间隔循环
     */
    private String scheduleMode;
    /**
     * LOOP 模式下的循环间隔（秒）
     */
    private Integer intervalSeconds;

    /**
     * 执行模式:
     * - COMMAND: 执行 command（shell:/agent:/request:）
     * - REQUEST: 模拟用户发起文本请求（使用 requestContent）
     */
    private String executionMode;
    private String command;
    /**
     * REQUEST 模式下的用户请求内容
     */
    private String requestContent;
    /**
     * REQUEST 模式是否走 orchestrator 路径
     */
    private Boolean requestUseOrchestrator;
    private Boolean enabled;
}
