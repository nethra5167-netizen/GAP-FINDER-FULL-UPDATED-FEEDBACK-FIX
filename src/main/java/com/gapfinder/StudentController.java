package com.gapfinder;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/student")
public class StudentController {

    private final JdbcTemplate db;
    private final GapDetectionService gapService;

    public StudentController(JdbcTemplate db, GapDetectionService gapService) {
        this.db = db;
        this.gapService = gapService;
    }

    private Long getStudentId(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        var list = db.queryForList("SELECT id FROM students WHERE user_id = ?", uid);
        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to student accounts.");
        }
        return ((Number) list.get(0).get("id")).longValue();
    }

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboard(HttpSession session) {
        Long studentId = getStudentId(session);

        // Profile
        Map<String, Object> profile = db.queryForMap(
                "SELECT s.id, u.full_name AS name, u.email, s.student_id_code, s.school, s.grade, s.section, s.link_code " +
                "FROM students s JOIN users u ON s.user_id = u.id WHERE s.id = ?", studentId);

        // Progress metrics
        Number testsAttendedNum = db.queryForObject(
                "SELECT COUNT(*) FROM test_attempts WHERE student_id = ?", Number.class, studentId);
        int testsAttended = testsAttendedNum != null ? testsAttendedNum.intValue() : 0;

        Number avgPctNum = db.queryForObject(
                "SELECT COALESCE(AVG(percentage), 0) FROM test_attempts WHERE student_id = ?", Number.class, studentId);
        double overallPercentage = avgPctNum != null ? Math.round(avgPctNum.doubleValue() * 10.0) / 10.0 : 0.0;

        int activitiesCompleted = testsAttended;

        Number achNum = db.queryForObject(
                "SELECT COUNT(*) FROM achievements WHERE student_id = ?", Number.class, studentId);
        int achievementsCount = achNum != null ? achNum.intValue() : 0;

        // Active Learning Gaps (Weak Concepts)
        List<Map<String, Object>> activeGaps = db.queryForList(
                "SELECT lg.id, lg.severity, lg.reason, lg.recommendation, lg.created_at, " +
                "c.name AS concept_name, t.name AS topic_name, ch.name AS chapter_name, s.name AS subject_name, s.color AS subject_color " +
                "FROM learning_gaps lg " +
                "JOIN concepts c ON lg.concept_id = c.id " +
                "JOIN topics t ON c.topic_id = t.id " +
                "JOIN chapters ch ON t.chapter_id = ch.id " +
                "JOIN subjects s ON ch.subject_id = s.id " +
                "WHERE lg.student_id = ? AND lg.status = 'ACTIVE' ORDER BY lg.severity DESC, lg.id DESC", studentId);

        // Strong Concepts (Proficient)
        List<Map<String, Object>> strongConcepts = db.queryForList(
                "SELECT cp.correct_attempts, cp.total_attempts, cp.proficiency_level, " +
                "c.name AS concept_name, t.name AS topic_name, s.name AS subject_name " +
                "FROM concept_performance cp " +
                "JOIN concepts c ON cp.concept_id = c.id " +
                "JOIN topics t ON c.topic_id = t.id " +
                "JOIN chapters ch ON t.chapter_id = ch.id " +
                "JOIN subjects s ON ch.subject_id = s.id " +
                "WHERE cp.student_id = ? AND cp.proficiency_level = 'PROFICIENT' LIMIT 5", studentId);

        // Recent Tests
        List<Map<String, Object>> recentAttempts = db.queryForList(
                "SELECT ta.id, t.title AS test_title, s.name AS subject_name, s.color AS subject_color, " +
                "ta.score, ta.total_marks, ta.percentage, ta.completed_at " +
                "FROM test_attempts ta " +
                "JOIN tests t ON ta.test_id = t.id " +
                "JOIN subjects s ON t.subject_id = s.id " +
                "WHERE ta.student_id = ? ORDER BY ta.id DESC LIMIT 5", studentId);

        // Available Tests count
        Number availNum = db.queryForObject(
                "SELECT COUNT(DISTINCT t.id) FROM tests t " +
                "JOIN test_assignments ta ON t.id = ta.test_id " +
                "JOIN class_students cs ON ta.class_id = cs.class_id " +
                "WHERE cs.student_id = ? AND t.is_published = TRUE", Number.class, studentId);
        int availableTestsCount = availNum != null ? availNum.intValue() : 0;

        Map<String, Object> res = new HashMap<>();
        res.put("profile", profile);
        res.put("testsAttended", testsAttended);
        res.put("overallPercentage", overallPercentage);
        res.put("activitiesCompleted", activitiesCompleted);
        res.put("achievementsCount", achievementsCount);
        res.put("availableTestsCount", availableTestsCount);
        res.put("activeGaps", activeGaps);
        res.put("strongConcepts", strongConcepts);
        res.put("recentAttempts", recentAttempts);

        return res;
    }

    @GetMapping("/subjects")
    public List<Map<String, Object>> getSubjects(HttpSession session) {
        Long studentId = getStudentId(session);

        List<Map<String, Object>> subjects = db.queryForList(
                "SELECT id, name, code, icon, color FROM subjects ORDER BY id ASC");

        for (Map<String, Object> sub : subjects) {
            Long subId = ((Number) sub.get("id")).longValue();

            Number subAvg = db.queryForObject(
                    "SELECT COALESCE(AVG(ta.percentage), 0) FROM test_attempts ta " +
                    "JOIN tests t ON ta.test_id = t.id WHERE ta.student_id = ? AND t.subject_id = ?",
                    Number.class, studentId, subId);
            sub.put("averageScore", subAvg != null ? Math.round(subAvg.doubleValue()) : 0);

            // Chapters and topics
            List<Map<String, Object>> chapters = db.queryForList(
                    "SELECT id, name, chapter_order FROM chapters WHERE subject_id = ? ORDER BY chapter_order ASC", subId);
            for (Map<String, Object> ch : chapters) {
                Long chId = ((Number) ch.get("id")).longValue();
                List<Map<String, Object>> topics = db.queryForList(
                        "SELECT id, name FROM topics WHERE chapter_id = ?", chId);
                for (Map<String, Object> top : topics) {
                    Long topId = ((Number) top.get("id")).longValue();
                    List<Map<String, Object>> concepts = db.queryForList(
                            "SELECT c.id, c.name, c.description, " +
                            "COALESCE(cp.proficiency_level, 'NOT_ATTEMPTED') AS status, " +
                            "COALESCE(cp.total_attempts, 0) AS total_attempts, " +
                            "COALESCE(cp.correct_attempts, 0) AS correct_attempts " +
                            "FROM concepts c " +
                            "LEFT JOIN concept_performance cp ON c.id = cp.concept_id AND cp.student_id = ? " +
                            "WHERE c.topic_id = ?", studentId, topId);
                    top.put("concepts", concepts);
                }
                ch.put("topics", topics);
            }
            sub.put("chapters", chapters);
        }

        return subjects;
    }

    @GetMapping("/tests")
    public Map<String, Object> getTests(HttpSession session) {
        Long studentId = getStudentId(session);

        // Available tests
        List<Map<String, Object>> available = db.queryForList(
                "SELECT DISTINCT t.id, t.title, s.name AS subject_name, s.code AS subject_code, s.color AS subject_color, " +
                "t.duration_minutes, t.total_marks, t.passing_marks, " +
                "(SELECT COUNT(*) FROM test_questions WHERE test_id = t.id) AS question_count " +
                "FROM tests t " +
                "JOIN subjects s ON t.subject_id = s.id " +
                "JOIN test_assignments ta ON t.id = ta.test_id " +
                "JOIN class_students cs ON ta.class_id = cs.class_id " +
                "WHERE cs.student_id = ? AND t.is_published = TRUE ORDER BY t.id ASC", studentId);

        // Attended history
        List<Map<String, Object>> history = db.queryForList(
                "SELECT ta.id AS attempt_id, t.id AS test_id, t.title AS test_title, s.name AS subject_name, s.color AS subject_color, " +
                "ta.score, ta.total_marks, ta.percentage, ta.completed_at " +
                "FROM test_attempts ta " +
                "JOIN tests t ON ta.test_id = t.id " +
                "JOIN subjects s ON t.subject_id = s.id " +
                "WHERE ta.student_id = ? ORDER BY ta.id DESC", studentId);

        return Map.of("available", available, "history", history);
    }

    @GetMapping("/tests/{id}")
    public Map<String, Object> getTestDetails(@PathVariable("id") Long testId, HttpSession session) {
        getStudentId(session);

        Map<String, Object> test = db.queryForMap(
                "SELECT t.id, t.title, s.name AS subject_name, s.color AS subject_color, " +
                "t.duration_minutes, t.total_marks, t.passing_marks " +
                "FROM tests t JOIN subjects s ON t.subject_id = s.id WHERE t.id = ?", testId);

        // Fetch questions WITHOUT revealing correct answer
        List<Map<String, Object>> questions = db.queryForList(
                "SELECT q.id, q.question_text, q.option_a, q.option_b, q.option_c, q.option_d, " +
                "c.name AS concept_name, t.name AS topic_name, tq.marks " +
                "FROM test_questions tq " +
                "JOIN questions q ON tq.question_id = q.id " +
                "JOIN concepts c ON q.concept_id = c.id " +
                "JOIN topics t ON c.topic_id = t.id " +
                "WHERE tq.test_id = ? ORDER BY tq.id ASC", testId);

        test.put("questions", questions);
        return test;
    }

    @PostMapping("/tests/{id}/submit")
    public Map<String, Object> submitTest(@PathVariable("id") Long testId,
                                          @RequestBody Map<String, String> answers,
                                          HttpSession session) {
        Long studentId = getStudentId(session);
        return gapService.evaluateTestAttempt(studentId, testId, answers);
    }

    @GetMapping("/achievements")
    public List<Map<String, Object>> getAchievements(HttpSession session) {
        Long studentId = getStudentId(session);
        return db.queryForList(
                "SELECT id, badge_name, badge_desc, icon, earned_at FROM achievements WHERE student_id = ? ORDER BY id DESC",
                studentId);
    }

    @GetMapping("/announcements")
    public List<Map<String, Object>> getAnnouncements(HttpSession session) {
        Long studentId = getStudentId(session);
        return db.queryForList(
                "SELECT a.id, a.title, a.content, a.created_at, u.full_name AS teacher_name, c.name AS class_name " +
                "FROM announcements a " +
                "JOIN teachers t ON a.teacher_id = t.id " +
                "JOIN users u ON t.user_id = u.id " +
                "JOIN classes c ON a.class_id = c.id " +
                "JOIN class_students cs ON c.id = cs.class_id " +
                "WHERE cs.student_id = ? ORDER BY a.id DESC", studentId);
    }
}
