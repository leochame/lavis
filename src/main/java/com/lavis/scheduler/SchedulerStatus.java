package com.lavis.scheduler;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SchedulerStatus {
    private int totalTasks;
    private int enabledTasks;
    private int runningTasks;
}
