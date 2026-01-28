package com.lavis.controller;

import com.lavis.skills.SkillExecutor;
import com.lavis.skills.SkillService;
import com.lavis.skills.dto.CreateSkillRequest;
import com.lavis.skills.dto.ExecuteSkillRequest;
import com.lavis.skills.dto.SkillResponse;
import com.lavis.skills.dto.UpdateSkillRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillsController {

    private final SkillService skillService;

    public SkillsController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public List<SkillResponse> getAllSkills(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String category) {
        if (category != null) {
            return skillService.getSkillsByCategory(category);
        }
        if (Boolean.TRUE.equals(enabled)) {
            return skillService.getEnabledSkills();
        }
        return skillService.getAllSkills();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SkillResponse> getSkill(@PathVariable String id) {
        return skillService.getSkill(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<SkillResponse> createSkill(@RequestBody CreateSkillRequest request) {
        SkillResponse created = skillService.createSkill(request);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SkillResponse> updateSkill(
            @PathVariable String id,
            @RequestBody UpdateSkillRequest request) {
        return skillService.updateSkill(id, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable String id) {
        if (skillService.deleteSkill(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<Map<String, Object>> executeSkill(
            @PathVariable String id,
            @RequestBody(required = false) ExecuteSkillRequest request) {
        Map<String, String> params = request != null ? request.getParams() : null;
        SkillExecutor.ExecutionResult result = skillService.executeSkill(id, params);

        Map<String, Object> response = new HashMap<>();
        response.put("success", result.isSuccess());
        response.put("output", result.getOutput());
        response.put("error", result.getError());
        response.put("durationMs", result.getDurationMs());

        if (result.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadSkills() {
        int count = skillService.reloadSkills();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Reloaded " + count + " skills");
        response.put("count", count);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/categories")
    public List<String> getCategories() {
        return skillService.getAllCategories();
    }

    @GetMapping("/by-name/{name}")
    public ResponseEntity<SkillResponse> getSkillByName(@PathVariable String name) {
        return skillService.getSkillByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
