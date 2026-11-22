package com.gapfinder;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/teacher")
public class TeacherController {

    private final JdbcTemplate db;

    public TeacherController(JdbcTemplate db) {
        this.db = db;
    }

    private Long getTeacherId(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        var list = db.queryForList("SELECT id FROM teachers WHERE user_id = ?", uid);
        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to teacher accounts.");
        }
        return ((Number) list.get(0).get("id")).longValue();
    }

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboard(HttpSession session) {
        Long teacherId = getTeacherId(session);

        // Teacher profile
        Map<String, Object> profile = db.queryForMap(
                "SELECT t.id, u.full_name AS name, t.teacher_id_code, t.school, t.subject " +
                "FROM teachers t JOIN users u ON t.user_id = u.id WHERE t.id = ?", teacherId);

        // Assigned classes
        List<Map<String, Object>> classes = db.queryForList(
                "SELECT c.id, c.name, c.grade, c.section, " +
                "(SELECT COUNT(*) FROM class_students WHERE class_id = c.id) AS student_count " +
                "FROM teacher_classes tc JOIN classes c ON tc.class_id = c.id WHERE tc.teacher_id = ?", teacherId);

        // Overall stats across teacher's classes
        Number totalStudentsNum = db.queryForObject(
                "SELECT COUNT(DISTINCT cs.student_id) FROM teacher_classes tc " +
                "JOIN class_students cs ON tc.class_id = cs.class_id WHERE tc.teacher_id = ?", Number.class, teacherId);
        int totalStudents = totalStudentsNum != null ? totalStudentsNum.intValue() : 0;

        Number testsCreatedNum = db.queryForObject(
                "SELECT COUNT(*) FROM tests WHERE created_by_teacher_id = ?", Number.class, teacherId);
        int testsCreated = testsCreatedNum != null ? testsCreatedNum.intValue() : 0;

        Number classAvgNum = db.queryForObject(
                "SELECT COALESCE(AVG(ta.percentage), 0) FROM test_attempts ta " +
                "JOIN class_students cs ON ta.student_id = cs.student_id " +
                "JOIN teacher_classes tc ON cs.class_id = tc.class_id " +
                "JOIN tests te ON te.id = ta.test_id " +
                "JOIN teacher_subjects ts ON ts.subject_id = te.subject_id AND ts.teacher_id = ? " +
                "WHERE tc.teacher_id = ? AND ta.status = 'APPROVED'", Number.class, teacherId, teacherId);
        double classAverage = classAvgNum != null ? Math.round(classAvgNum.doubleValue() * 10.0) / 10.0 : 0.0;

        Number supportNum = db.queryForObject(
                "SELECT COUNT(DISTINCT lg.student_id) FROM learning_gaps lg " +
                "JOIN class_students cs ON lg.student_id = cs.student_id " +
                "JOIN teacher_classes tc ON cs.class_id = tc.class_id " +
                "JOIN concepts c ON c.id = lg.concept_id JOIN topics tp ON tp.id = c.topic_id " +
                "JOIN chapters ch ON ch.id = tp.chapter_id JOIN teacher_subjects ts ON ts.subject_id = ch.subject_id AND ts.teacher_id = ? " +
                "WHERE tc.teacher_id = ? AND lg.status = 'ACTIVE'", Number.class, teacherId, teacherId);
        int studentsNeedingSupport = supportNum != null ? supportNum.intValue() : 0;

        // Most Common Weak Topics across assigned classes
        List<Map<String, Object>> commonGaps = db.queryForList(
                "SELECT t.name AS topic_name, c.name AS concept_name, s.name AS subject_name, " +
                "COUNT(DISTINCT lg.student_id) AS struggling_students_count " +
                "FROM learning_gaps lg " +
                "JOIN concepts c ON lg.concept_id = c.id " +
                "JOIN topics t ON c.topic_id = t.id " +
                "JOIN chapters ch ON t.chapter_id = ch.id " +
                "JOIN subjects s ON ch.subject_id = s.id " +
                "JOIN class_students cs ON lg.student_id = cs.student_id " +
                "JOIN teacher_classes tc ON cs.class_id = tc.class_id " +
                "JOIN teacher_subjects ts ON ts.subject_id = s.id AND ts.teacher_id = ? " +
                "WHERE tc.teacher_id = ? AND lg.status = 'ACTIVE' " +
                "GROUP BY t.name, c.name, s.name ORDER BY struggling_students_count DESC LIMIT 5", teacherId, teacherId);

        Map<String, Object> res = new HashMap<>();
        res.put("profile", profile);
        res.put("classes", classes);
        res.put("totalStudents", totalStudents);
        res.put("testsCreated", testsCreated);
        res.put("classAverage", classAverage);
        res.put("studentsNeedingSupport", studentsNeedingSupport);
        res.put("commonGaps", commonGaps);

        return res;
    }

    @GetMapping("/students")
    public List<Map<String, Object>> getStudents(HttpSession session) {
        Long teacherId = getTeacherId(session);

        List<Map<String, Object>> students = db.queryForList(
                "SELECT DISTINCT s.id, u.full_name AS student_name, s.student_id_code, c.name AS class_name, " +
                "COALESCE(ROUND(AVG(ta.percentage), 1), 0) AS overall_performance, " +
                "COUNT(ta.id) AS tests_taken, " +
                "(SELECT score FROM test_attempts WHERE student_id = s.id ORDER BY id DESC LIMIT 1) AS last_score, " +
                "(SELECT total_marks FROM test_attempts WHERE student_id = s.id ORDER BY id DESC LIMIT 1) AS last_total, " +
                "(SELECT COUNT(*) FROM learning_gaps WHERE student_id = s.id AND status = 'ACTIVE') AS active_gaps_count " +
                "FROM teacher_classes tc " +
                "JOIN class_students cs ON tc.class_id = cs.class_id " +
                "JOIN classes c ON cs.class_id = c.id " +
                "JOIN students s ON cs.student_id = s.id " +
                "JOIN users u ON s.user_id = u.id " +
                "LEFT JOIN test_attempts ta ON s.id = ta.student_id " +
                "WHERE tc.teacher_id = ? GROUP BY s.id, u.full_name, s.student_id_code, c.name", teacherId);

        for (Map<String, Object> stu : students) {
            Long stuId = ((Number) stu.get("id")).longValue();
            var weakRows = db.queryForList(
                    "SELECT t.name AS topic_name FROM learning_gaps lg " +
                    "JOIN concepts c ON lg.concept_id = c.id " +
                    "JOIN topics t ON c.topic_id = t.id " +
                    "WHERE lg.student_id = ? AND lg.status = 'ACTIVE' ORDER BY lg.severity DESC LIMIT 1", stuId);
            stu.put("weakestTopic", weakRows.isEmpty() ? "None Identified" : weakRows.get(0).get("topic_name"));

            int gaps = ((Number) stu.get("active_gaps_count")).intValue();
            double perf = ((Number) stu.get("overall_performance")).doubleValue();
            String status = gaps > 0 || (perf < 60.0 && perf > 0) ? "Needs Support" : (perf >= 80.0 ? "Proficient" : "Developing");
            stu.put("status", status);
        }

        return students;
    }

    @GetMapping("/support-needed")
    public List<Map<String, Object>> getStudentsNeedingSupport(HttpSession session) {
        Long teacherId = getTeacherId(session);

        return db.queryForList(
                "SELECT lg.id, u.full_name AS student_name, s.student_id_code, c_class.name AS class_name, " +
                "s_sub.name AS weak_subject, t_top.name AS weak_topic, c_con.name AS weak_concept, " +
                "lg.severity, lg.reason AS assessment_evidence, lg.recommendation AS recommended_intervention " +
                "FROM learning_gaps lg " +
                "JOIN students s ON lg.student_id = s.id " +
                "JOIN users u ON s.user_id = u.id " +
                "JOIN class_students cs ON s.id = cs.student_id " +
                "JOIN classes c_class ON cs.class_id = c_class.id " +
                "JOIN teacher_classes tc ON c_class.id = tc.class_id " +
                "JOIN concepts c_con ON lg.concept_id = c_con.id " +
                "JOIN topics t_top ON c_con.topic_id = t_top.id " +
                "JOIN chapters ch ON t_top.chapter_id = ch.id " +
                "JOIN subjects s_sub ON ch.subject_id = s_sub.id " +
                "JOIN teacher_subjects ts ON ts.subject_id = s_sub.id AND ts.teacher_id = ? " +
                "WHERE tc.teacher_id = ? AND lg.status = 'ACTIVE' " +
                "ORDER BY lg.severity DESC, lg.id DESC", teacherId, teacherId);
    }

    @GetMapping("/analytics")
    public Map<String, Object> getAnalytics(HttpSession session) {
        Long teacherId = getTeacherId(session);

        List<Map<String, Object>> subjectStats = db.queryForList(
                "SELECT s.name AS subject_name, s.color, " +
                "COALESCE(ROUND(AVG(ta.percentage), 1), 0) AS class_average " +
                "FROM subjects s " +
                "JOIN teacher_subjects ts ON ts.subject_id = s.id AND ts.teacher_id = ? " +
                "LEFT JOIN tests t ON s.id = t.subject_id " +
                "LEFT JOIN test_attempts ta ON t.id = ta.test_id AND ta.status = 'APPROVED' " +
                "JOIN class_students cs ON ta.student_id = cs.student_id " +
                "JOIN teacher_classes tc ON cs.class_id = tc.class_id " +
                "WHERE tc.teacher_id = ? GROUP BY s.id, s.name, s.color", teacherId, teacherId);

        List<Map<String, Object>> topicAnalysis = db.queryForList(
                "SELECT t.name AS topic_name, s.name AS subject_name, " +
                "COUNT(sa.id) AS total_answers, " +
                "ROUND((SUM(CASE WHEN sa.is_correct = TRUE THEN 1 ELSE 0 END) / COUNT(sa.id)) * 100.0, 1) AS accuracy_percentage " +
                "FROM topics t " +
                "JOIN chapters ch ON t.chapter_id = ch.id " +
                "JOIN subjects s ON ch.subject_id = s.id " +
                "JOIN teacher_subjects ts ON ts.subject_id = s.id AND ts.teacher_id = ? " +
                "JOIN concepts c ON t.id = c.topic_id " +
                "JOIN questions q ON c.id = q.concept_id " +
                "JOIN student_answers sa ON q.id = sa.question_id " +
                "JOIN test_attempts ta ON sa.attempt_id = ta.id AND ta.status = 'APPROVED' " +
                "JOIN class_students cs ON ta.student_id = cs.student_id " +
                "JOIN teacher_classes tc ON cs.class_id = tc.class_id " +
                "WHERE tc.teacher_id = ? " +
                "GROUP BY t.id, t.name, s.name ORDER BY accuracy_percentage ASC", teacherId, teacherId);

        return Map.of("subjectStats", subjectStats, "topicAnalysis", topicAnalysis);
    }

    @PostMapping("/tests/create")
    public Map<String, Object> createTest(@RequestBody CreateTestPayload payload, HttpSession session) {
        Long teacherId = getTeacherId(session);

        if (payload.title == null || payload.title.isBlank() || payload.subjectId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test title and subject are required.");
        }

        int duration = payload.durationMinutes > 0 ? payload.durationMinutes : 20;
        int totalMarks = payload.questionIds != null && !payload.questionIds.isEmpty() ? payload.questionIds.size() * 10 : 50;
        int passingMarks = (int)(totalMarks * 0.4);

        db.update("INSERT INTO tests(title, subject_id, duration_minutes, total_marks, passing_marks, created_by_teacher_id, is_published) " +
                "VALUES(?, ?, ?, ?, ?, ?, TRUE)",
                payload.title.trim(), payload.subjectId, duration, totalMarks, passingMarks, teacherId);

        Long testId = db.queryForObject("SELECT id FROM tests WHERE created_by_teacher_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, teacherId);

        // Assign questions
        if (payload.questionIds != null) {
            for (Long qId : payload.questionIds) {
                db.update("INSERT IGNORE INTO test_questions(test_id, question_id, marks) VALUES(?, ?, 10)", testId, qId);
            }
        }

        // Assign to teacher classes
        Long classId = payload.classId;
        if (classId == null) {
            classId = db.queryForObject("SELECT class_id FROM teacher_classes WHERE teacher_id = ? LIMIT 1", Long.class, teacherId);
        }
        if (classId != null) {
            db.update("INSERT IGNORE INTO test_assignments(test_id, class_id) VALUES(?, ?)", testId, classId);
        }

        return Map.of("message", "Test created and published successfully!", "testId", testId);
    }

    @GetMapping("/questions-pool")
    public List<Map<String, Object>> getQuestionsPool(@RequestParam(value = "subjectId", required = false) Long subjectId,
                                                      HttpSession session) {
        getTeacherId(session);
        String sql = "SELECT q.id, q.question_text, c.name AS concept_name, t.name AS topic_name, s.name AS subject_name, s.id AS subject_id " +
                "FROM questions q " +
                "JOIN concepts c ON q.concept_id = c.id " +
                "JOIN topics t ON c.topic_id = t.id " +
                "JOIN chapters ch ON t.chapter_id = ch.id " +
                "JOIN subjects s ON ch.subject_id = s.id ";
        if (subjectId != null) {
            sql += "WHERE s.id = ? ORDER BY q.id ASC";
            return db.queryForList(sql, subjectId);
        } else {
            sql += "ORDER BY s.id, q.id ASC";
            return db.queryForList(sql);
        }
    }

    public static class CreateTestPayload {
        public String title;
        public Long subjectId;
        public int durationMinutes;
        public Long classId;
        public List<Long> questionIds;
    }
}
