package com.gapfinder;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class GapDetectionService {

    private final JdbcTemplate db;

    public GapDetectionService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Object> evaluateTestAttempt(Long studentId, Long testId, Map<String, String> submittedAnswers) {
        // 1. Fetch test questions with concept metadata and correct option
        String sql = "SELECT q.id, q.concept_id, c.name AS concept_name, t.name AS topic_name, " +
                "ch.name AS chapter_name, s.name AS subject_name, q.correct_option, q.explanation, " +
                "q.question_text, q.option_a, q.option_b, q.option_c, q.option_d, tq.marks " +
                "FROM test_questions tq " +
                "JOIN questions q ON tq.question_id = q.id " +
                "JOIN concepts c ON q.concept_id = c.id " +
                "JOIN topics t ON c.topic_id = t.id " +
                "JOIN chapters ch ON t.chapter_id = ch.id " +
                "JOIN subjects s ON ch.subject_id = s.id " +
                "WHERE tq.test_id = ?";

        List<Map<String, Object>> questions = db.queryForList(sql, testId);
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("No questions configured for this test.");
        }

        int totalMarks = 0;
        int earnedMarks = 0;
        List<Map<String, Object>> questionBreakdown = new ArrayList<>();
        Set<Long> affectedConceptIds = new HashSet<>();

        for (Map<String, Object> q : questions) {
            Long qId = ((Number) q.get("id")).longValue();
            Long conceptId = ((Number) q.get("concept_id")).longValue();
            affectedConceptIds.add(conceptId);
            int marks = ((Number) q.get("marks")).intValue();
            totalMarks += marks;

            String correctOpt = String.valueOf(q.get("correct_option")).trim().toUpperCase();
            String studentOpt = submittedAnswers.getOrDefault(String.valueOf(qId), "").trim().toUpperCase();
            boolean isCorrect = !studentOpt.isEmpty() && studentOpt.equals(correctOpt);

            if (isCorrect) {
                earnedMarks += marks;
            }

            Map<String, Object> item = new HashMap<>();
            item.put("questionId", qId);
            item.put("questionText", q.get("question_text"));
            item.put("selectedOption", studentOpt.isEmpty() ? "Unanswered" : studentOpt);
            item.put("correctOption", correctOpt);
            item.put("isCorrect", isCorrect);
            item.put("explanation", q.get("explanation"));
            item.put("conceptName", q.get("concept_name"));
            item.put("topicName", q.get("topic_name"));
            item.put("marksEarned", isCorrect ? marks : 0);
            item.put("marksPossible", marks);
            questionBreakdown.add(item);
        }

        double percentage = totalMarks > 0 ? ((double) earnedMarks / totalMarks) * 100.0 : 0.0;

        // 2. Insert test attempt record
        db.update("INSERT INTO test_attempts(test_id, student_id, score, total_marks, percentage, status, completed_at) VALUES(?, ?, ?, ?, ?, 'PENDING_REVIEW', NOW())",
                testId, studentId, earnedMarks, totalMarks, Math.round(percentage * 100.0) / 100.0);
        Long attemptId = db.queryForObject("SELECT id FROM test_attempts WHERE student_id = ? AND test_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, studentId, testId);

        // 3. Insert student answers
        for (Map<String, Object> item : questionBreakdown) {
            Long qId = (Long) item.get("questionId");
            String sel = (String) item.get("selectedOption");
            String storedOption = (sel.length() == 1) ? sel : "X";
            boolean correct = (Boolean) item.get("isCorrect");
            db.update("INSERT INTO student_answers(attempt_id, question_id, selected_option, is_correct) VALUES(?, ?, ?, ?)",
                    attemptId, qId, storedOption, correct);
        }

        // 4. Run Automatic Gap Detection across affected concepts
        List<Map<String, Object>> detectedGaps = new ArrayList<>();
        List<String> resolvedGaps = new ArrayList<>();

        for (Long conceptId : affectedConceptIds) {
            String statSql = "SELECT COUNT(sa.id) AS total, " +
                    "SUM(CASE WHEN sa.is_correct = TRUE THEN 1 ELSE 0 END) AS correct " +
                    "FROM student_answers sa " +
                    "JOIN test_attempts ta ON sa.attempt_id = ta.id " +
                    "JOIN questions q ON sa.question_id = q.id " +
                    "WHERE ta.student_id = ? AND q.concept_id = ?";

            Map<String, Object> stats = db.queryForMap(statSql, studentId, conceptId);
            int totalAttempts = ((Number) stats.get("total")).intValue();
            int correctAttempts = stats.get("correct") == null ? 0 : ((Number) stats.get("correct")).intValue();
            double conceptPct = totalAttempts > 0 ? ((double) correctAttempts / totalAttempts) * 100.0 : 0.0;

            String proficiency;
            if (totalAttempts < 2) {
                proficiency = "INSUFFICIENT_DATA";
            } else if (conceptPct < 60.0) {
                proficiency = "NEEDS_IMPROVEMENT";
            } else if (conceptPct < 80.0) {
                proficiency = "DEVELOPING";
            } else {
                proficiency = "PROFICIENT";
            }

            db.update("INSERT INTO concept_performance(student_id, concept_id, total_attempts, correct_attempts, proficiency_level, last_evaluated_at) " +
                    "VALUES(?, ?, ?, ?, ?, NOW()) " +
                    "ON DUPLICATE KEY UPDATE total_attempts = VALUES(total_attempts), correct_attempts = VALUES(correct_attempts), " +
                    "proficiency_level = VALUES(proficiency_level), last_evaluated_at = NOW()",
                    studentId, conceptId, totalAttempts, correctAttempts, proficiency);

            Map<String, Object> conceptMeta = db.queryForMap(
                    "SELECT c.name AS concept_name, t.name AS topic_name, ch.name AS chapter_name, s.name AS subject_name " +
                    "FROM concepts c JOIN topics t ON c.topic_id = t.id " +
                    "JOIN chapters ch ON t.chapter_id = ch.id " +
                    "JOIN subjects s ON ch.subject_id = s.id WHERE c.id = ?", conceptId);

            String cName = (String) conceptMeta.get("concept_name");
            String tName = (String) conceptMeta.get("topic_name");
            String sName = (String) conceptMeta.get("subject_name");

            if ("NEEDS_IMPROVEMENT".equals(proficiency)) {
                String severity = conceptPct < 40.0 ? "HIGH" : "MEDIUM";
                String reason = String.format("Accuracy is %.0f%% across %d attempts in '%s' (%s). Repeated difficulty identified in conceptual application.",
                        conceptPct, totalAttempts, cName, tName);
                String recommendation = String.format("Revise '%s' in %s. Review step-by-step worked examples and solve 3 targeted practice drills.",
                        cName, sName);

                Integer existingGap = db.queryForObject(
                        "SELECT COUNT(*) FROM learning_gaps WHERE student_id = ? AND concept_id = ? AND status = 'ACTIVE'",
                        Integer.class, studentId, conceptId);
                if (existingGap == null || existingGap == 0) {
                    db.update("INSERT INTO learning_gaps(student_id, concept_id, severity, reason, recommendation, status) VALUES(?, ?, ?, ?, ?, 'ACTIVE')",
                            studentId, conceptId, severity, reason, recommendation);
                } else {
                    db.update("UPDATE learning_gaps SET severity = ?, reason = ?, recommendation = ?, created_at = NOW() WHERE student_id = ? AND concept_id = ? AND status = 'ACTIVE'",
                            severity, reason, recommendation, studentId, conceptId);
                }

                Map<String, Object> gapInfo = new HashMap<>();
                gapInfo.put("conceptName", cName);
                gapInfo.put("topicName", tName);
                gapInfo.put("subjectName", sName);
                gapInfo.put("severity", severity);
                gapInfo.put("reason", reason);
                gapInfo.put("recommendation", recommendation);
                detectedGaps.add(gapInfo);

            } else if ("PROFICIENT".equals(proficiency)) {
                int resolvedCount = db.update("UPDATE learning_gaps SET status = 'RESOLVED' WHERE student_id = ? AND concept_id = ? AND status = 'ACTIVE'",
                        studentId, conceptId);
                if (resolvedCount > 0) {
                    resolvedGaps.add(cName);
                    db.update("INSERT IGNORE INTO achievements(student_id, badge_name, badge_desc, icon) VALUES(?, 'Gap Closer', 'Successfully mastered and closed an identified learning gap!', 'check-circle')",
                            studentId);
                }
            }
        }

        Integer totalAttemptsCount = db.queryForObject("SELECT COUNT(*) FROM test_attempts WHERE student_id = ?", Integer.class, studentId);
        if (totalAttemptsCount != null && totalAttemptsCount >= 3) {
            db.update("INSERT IGNORE INTO achievements(student_id, badge_name, badge_desc, icon) VALUES(?, 'Assessment Veteran', 'Completed 3 or more diagnostic assessments', 'award')", studentId);
        }
        if (earnedMarks == totalMarks) {
            db.update("INSERT IGNORE INTO achievements(student_id, badge_name, badge_desc, icon) VALUES(?, 'Perfectionist', 'Scored a perfect 100% on a diagnostic test!', 'zap')", studentId);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("attemptId", attemptId);
        response.put("reviewStatus", "PENDING_REVIEW");
        response.put("score", earnedMarks);
        response.put("totalMarks", totalMarks);
        response.put("percentage", Math.round(percentage * 10.0) / 10.0);
        response.put("passed", earnedMarks >= totalMarks * 0.4);
        response.put("breakdown", questionBreakdown);
        response.put("detectedGaps", detectedGaps);
        response.put("resolvedGaps", resolvedGaps);
        return response;
    }
}
