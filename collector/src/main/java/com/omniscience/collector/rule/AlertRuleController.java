package com.omniscience.collector.rule;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.omniscience.collector.config.MonitorProps;
import com.omniscience.collector.promql.PromQLException;

/** Rule CRUD backing the Alerts Feed "Rule Configuration" panel. */
@RestController
@RequestMapping("/api/v1/rules")
public class AlertRuleController {

    private final AlertRuleService service;
    private final String orgId;

    public AlertRuleController(AlertRuleService service, MonitorProps props) {
        this.service = service;
        // Single tenant until slice 7 introduces the real tenant context.
        this.orgId = props.orgId();
    }

    public record RuleRequest(String ruleKey, String name, String expression, String severity,
                              int forSeconds, int resolveSeconds, int cooldownSeconds, Boolean enabled) {

        AlertRule toRule(String orgId) {
            return new AlertRule(null, orgId, ruleKey, name, expression, severity,
                    forSeconds, resolveSeconds, cooldownSeconds, enabled == null || enabled);
        }
    }

    @GetMapping
    public List<AlertRule> list() {
        return service.findAll(orgId);
    }

    @GetMapping("/{ruleKey}")
    public ResponseEntity<AlertRule> get(@PathVariable String ruleKey) {
        return service.find(orgId, ruleKey)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody RuleRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.toRule(orgId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{ruleKey}")
    public ResponseEntity<?> update(@PathVariable String ruleKey, @RequestBody RuleRequest request) {
        try {
            return ResponseEntity.ok(service.update(orgId, ruleKey, request.toRule(orgId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{ruleKey}")
    public ResponseEntity<Void> delete(@PathVariable String ruleKey) {
        return service.delete(orgId, ruleKey)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Parse-only check so the editor can validate before saving. */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, String> body) {
        String expression = body.getOrDefault("expression", "");
        try {
            service.validateExpression(expression);
            return ResponseEntity.ok(Map.of("valid", true, "expression", expression));
        } catch (PromQLException e) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", e.getMessage()));
        }
    }
}
