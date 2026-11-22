package com.gapfinder;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/parent")
public class ParentController {

    private final JdbcTemplate db;

    public ParentController(JdbcTemplate db) {
        this.db = db;
    }

    private Long getParentId(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        var list = db.queryForList("SELECT id FROM parents WHERE user_id = ?", uid);
        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to parent accounts.");
        }
        return ((Number) list.get(0).get("id")).longValue();
    }

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboard(@RequestParam(value = "studentId", required = false) Long targetStudentId,
                                            HttpSession session) {
        Long parentId = getParentId(session);

        // Fetch all linked children for this parent
        List<Map<String, Object>> children = db.queryForList(
                "SELECT s.id AS student_id, u.full_name AS child_name, s.student_id_code, s.grade, s.section, s.school " +
                "FROM parent_student_links psl " +
                "JOIN students s ON psl.student_id = s.id " +
                "JOIN users u ON s.user_id = u.id " +
                "WHERE psl.parent_id = ? AND psl.status = 'ACTIVE'", parentId);

        if (children.isEmpty()) {
            return Map.of("hasLinkedChild", false, "children", List.of(),
                    "message", "No child is currently linked to your parent account. Please link your child using their unique Link Code.");
        }

        // Determine active child
        Long activeStudentId = targetStudentId;
        if (activeStudentId == null) {
            activeStudentId = ((Number) children.get(0).get("student_id")).longValue();
        } else {
            Long finalChildId = activeStudentId;
            boolean authorized = children.stream().anyMatch(c -> ((Number) c.get("student_id")).longValue() == finalChildId);
            if (!authorized) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized: You can only access academic records of your linked child.");
            }
        }

        // Active child profile
        Map<String, Object> childProfile = db.queryForMap(
                "SELECT s.id, u.full_name AS name, s.student_id_code, s.school, s.grade, s.section " +
                "FROM students s JOIN users u ON s.user_id = u.id WHERE s.id = ?", activeStudentId);

        // Performance summary
        Number totalTestsNum = db.queryForObject("SELECT COUNT(*) FROM test_attempts WHERE student_id = ? AND status='APPROVED'", Number.class, activeStudentId);
        int totalTests = totalTestsNum != null ? totalTestsNum.intValue() : 0;

        Number overallAvgNum = db.queryForObject("SELECT COALESCE(AVG(percentage), 0) FROM test_attempts WHERE student_id = ? AND status='APPROVED'", Number.class, activeStudentId);
        double overallAverage = overallAvgNum != null ? Math.round(overallAvgNum.doubleValue() * 10.0) / 10.0 : 0.0;

        Number achNum = db.queryForObject("SELECT COUNT(*) FROM achievements WHERE student_id = ?", Number.class, activeStudentId);
        int achievementsCount = achNum != null ? achNum.intValue() : 0;

        // Subject-wise performance breakdown
        List<Map<String, Object>> subjectBreakdown = db.queryForList(
                "SELECT s.name AS subject_name, s.color, " +
                "COALESCE(ROUND(AVG(ta.percentage), 1), 0) AS average_score, " +
                "COUNT(ta.id) AS tests_count " +
                "FROM subjects s " +
                "LEFT JOIN tests t ON s.id = t.subject_id " +
                "LEFT JOIN test_attempts ta ON t.id = ta.test_id AND ta.student_id = ? AND ta.status='APPROVED' " +
                "GROUP BY s.id, s.name, s.color ORDER BY s.id ASC", activeStudentId);

        // Identified Weak Concepts needing attention at home
        List<Map<String, Object>> weakConcepts = db.queryForList(
                "SELECT lg.id, lg.severity, lg.reason, lg.recommendation, lg.created_at, " +
                "c.name AS concept_name, t.name AS topic_name, s.name AS subject_name, s.color AS subject_color " +
                "FROM learning_gaps lg " +
                "JOIN concepts c ON lg.concept_id = c.id " +
                "JOIN topics t ON c.topic_id = t.id " +
                "JOIN chapters ch ON t.chapter_id = ch.id " +
                "JOIN subjects s ON ch.subject_id = s.id " +
                "WHERE lg.student_id = ? AND lg.status = 'ACTIVE' ORDER BY lg.severity DESC", activeStudentId);

        // Recent assessment history
        List<Map<String, Object>> testHistory = db.queryForList(
                "SELECT ta.id, t.title AS test_title, s.name AS subject_name, s.color AS subject_color, " +
                "ta.score, ta.total_marks, ta.percentage, ta.completed_at " +
                "FROM test_attempts ta " +
                "JOIN tests t ON ta.test_id = t.id " +
                "JOIN subjects s ON t.subject_id = s.id " +
                "WHERE ta.student_id = ? AND ta.status='APPROVED' ORDER BY ta.id DESC LIMIT 6", activeStudentId);

        Map<String, Object> res = new HashMap<>();
        res.put("hasLinkedChild", true);
        res.put("children", children);
        res.put("activeChild", childProfile);
        res.put("totalTests", totalTests);
        res.put("overallAverage", overallAverage);
        res.put("achievementsCount", achievementsCount);
        res.put("subjectBreakdown", subjectBreakdown);
        res.put("weakConcepts", weakConcepts);
        res.put("testHistory", testHistory);

        return res;
    }

    @PostMapping("/link-child")
    public Map<String, Object> linkChild(@RequestBody Map<String, String> payload, HttpSession session) {
        Long parentId = getParentId(session);
        String linkCode = payload.get("linkCode");
        if (linkCode == null || linkCode.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Student link code is required.");
        }

        var stuList = db.queryForList("SELECT id, user_id FROM students WHERE link_code = ?", linkCode.trim().toUpperCase());
        if (stuList.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No student account found with the provided Link Code. Please verify the code with your child.");
        }

        Long studentId = ((Number) stuList.get(0).get("id")).longValue();

        db.update("INSERT INTO parent_student_links(parent_id, student_id, status) VALUES(?, ?, 'ACTIVE') " +
                "ON DUPLICATE KEY UPDATE status = 'ACTIVE'", parentId, studentId);

        String childName = db.queryForObject(
                "SELECT u.full_name FROM students s JOIN users u ON s.user_id = u.id WHERE s.id = ?",
                String.class, studentId);

        return Map.of("message", "Successfully linked with " + childName, "studentId", studentId);
    }
}
