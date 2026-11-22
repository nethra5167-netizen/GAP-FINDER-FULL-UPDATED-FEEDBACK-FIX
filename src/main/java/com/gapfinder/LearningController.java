package com.gapfinder;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api")
public class LearningController {

    private final JdbcTemplate db;

    public LearningController(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> legacyDashboard(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        Long userId = (Long) uid;
        Map<String, Object> user = db.queryForMap(
                "SELECT id, full_name AS name, email, role FROM users WHERE id = ?", userId);
        return Map.of("user", user);
    }

    @PostMapping("/ai/explain-concept")
    public Map<String, Object> explainConcept(@RequestBody Map<String, String> payload, HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        String conceptName = payload.getOrDefault("conceptName", "Algebraic Equations");
        String subject = payload.getOrDefault("subject", "Mathematics");

        String explanation = String.format(
                "In %s, '%s' is a foundational concept. The core principle involves understanding the underlying relationship between variables, preserving balance across operations, and applying systematic decomposition steps. When solving problems, first identify the given variables, state known formulas, isolate unknowns step-by-step, and verify your result by substituting it back into the original expression.",
                subject, conceptName);

        List<String> tips = List.of(
                "Break complex questions into smaller single-operation steps.",
                "Always check operations performed on both sides of an equation.",
                "Review the diagnostic explanation after every test attempt."
        );

        return Map.of("concept", conceptName, "subject", subject, "explanation", explanation, "studyTips", tips);
    }
}
