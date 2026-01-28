package com.lavis.skills.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ExecuteSkillRequest {
    private Map<String, String> params;
}
