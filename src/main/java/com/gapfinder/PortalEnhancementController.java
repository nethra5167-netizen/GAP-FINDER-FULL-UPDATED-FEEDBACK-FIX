package com.gapfinder;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/portal")
public class PortalEnhancementController {

    private final JdbcTemplate db;

    private static final Path UPLOAD_DIR =
            Paths.get("uploads", "study-materials")
                    .toAbsolutePath().normalize();

    public PortalEnhancementController(JdbcTemplate db) {
        this.db = db;
    }

    private Long getUserId(HttpSession session) {
        Object id = session.getAttribute("userId");

        if (id == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Please log in.");
        }

        return ((Number) id).longValue();
    }

    private Long getTeacherId(HttpSession session) {
        return db.queryForObject(
                "SELECT id FROM teachers WHERE user_id=?",
                Long.class, getUserId(session));
    }

    private Long getStudentId(HttpSession session) {
        return db.queryForObject(
                "SELECT id FROM students WHERE user_id=?",
                Long.class, getUserId(session));
    }

    private Long getParentId(HttpSession session) {
        return db.queryForObject(
                "SELECT id FROM parents WHERE user_id=?",
                Long.class, getUserId(session));
    }

    private void checkSubject(Long teacherId, Long subjectId) {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM teacher_subjects " +
                "WHERE teacher_id=? AND subject_id=?",
                Integer.class, teacherId, subjectId);

        if (count == null || count == 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You are not assigned to this subject.");
        }
    }

    // TEACHER: Assigned subjects

    @GetMapping("/teacher/subjects")
    public List<Map<String,Object>> teacherSubjects(
            HttpSession session) {

        return db.queryForList(
                "SELECT s.id, s.name, s.code " +
                "FROM teacher_subjects ts " +
                "JOIN subjects s ON s.id=ts.subject_id " +
                "WHERE ts.teacher_id=?",
                getTeacherId(session));
    }

    // TEACHER: Students by subject

    @GetMapping("/teacher/students")
    public List<Map<String,Object>> teacherStudents(
            @RequestParam Long subjectId,
            HttpSession session) {

        Long teacherId = getTeacherId(session);
        checkSubject(teacherId, subjectId);

        return db.queryForList(
                "SELECT DISTINCT st.id AS student_id, " +
                "u.full_name AS student_name, " +
                "st.student_id_code, cl.name AS class_name, " +
                "COALESCE(ROUND(AVG(ta.percentage),1),0) " +
                "AS average_score, " +
                "COUNT(DISTINCT ta.id) AS tests_taken " +
                "FROM teacher_classes tc " +
                "JOIN class_students cs ON cs.class_id=tc.class_id " +
                "JOIN classes cl ON cl.id=cs.class_id " +
                "JOIN students st ON st.id=cs.student_id " +
                "JOIN users u ON u.id=st.user_id " +
                "LEFT JOIN tests te ON te.subject_id=? " +
                "LEFT JOIN test_attempts ta ON ta.test_id=te.id " +
                "AND ta.student_id=st.id " +
                "AND ta.status='APPROVED' " +
                "WHERE tc.teacher_id=? " +
                "GROUP BY st.id,u.full_name,st.student_id_code,cl.name",
                subjectId, teacherId);
    }

    // TEACHER: Create test manually

    @Transactional
    @PostMapping("/teacher/tests/manual")
    public Map<String,Object> createTest(
            @RequestBody ManualTestRequest req,
            HttpSession session) {

        Long teacherId = getTeacherId(session);

        checkSubject(teacherId, req.subjectId);

        if (req.title == null || req.title.isBlank()
                || req.questions == null || req.questions.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Enter test title and questions.");
        }

        if (req.durationMinutes < 1 ||
                req.durationMinutes > 240) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid test duration.");
        }

        List<Map<String,Object>> concepts =
                db.queryForList(
                "SELECT c.id FROM concepts c " +
                "JOIN topics tp ON tp.id=c.topic_id " +
                "JOIN chapters ch ON ch.id=tp.chapter_id " +
                "WHERE ch.subject_id=? " +
                "AND (? IS NULL OR ch.id=?) " +
                "ORDER BY c.id LIMIT 1",
                req.subjectId, req.chapterId, req.chapterId);

        if (concepts.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No concept found for this chapter.");
        }

        Long conceptId =
                ((Number) concepts.get(0).get("id")).longValue();

        int totalMarks = 0;

        for (ManualQuestion q : req.questions) {
            if (q.text == null || q.text.isBlank()
                    || blank(q.a) || blank(q.b)
                    || blank(q.c) || blank(q.d)
                    || q.correct == null
                    || !List.of("A","B","C","D")
                    .contains(q.correct.toUpperCase())
                    || q.marks < 1) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Check all question fields and answers.");
            }

            totalMarks += q.marks;
        }

        db.update(
                "INSERT INTO tests(title,subject_id,chapter_id," +
                "duration_minutes,total_marks,passing_marks," +
                "created_by_teacher_id,is_published) " +
                "VALUES(?,?,?,?,?,?,?,TRUE)",
                req.title.trim(), req.subjectId, req.chapterId,
                req.durationMinutes, totalMarks,
                Math.max(1, (int)Math.ceil(totalMarks * 0.4)),
                teacherId);

        Long testId = db.queryForObject(
                "SELECT LAST_INSERT_ID()", Long.class);

        for (ManualQuestion q : req.questions) {

            db.update(
                    "INSERT INTO questions(concept_id,question_text," +
                    "option_a,option_b,option_c,option_d," +
                    "correct_option,explanation) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                    conceptId, q.text.trim(),
                    q.a.trim(), q.b.trim(), q.c.trim(), q.d.trim(),
                    q.correct.toUpperCase(),
                    q.explanation == null ? "" : q.explanation);

            Long questionId = db.queryForObject(
                    "SELECT LAST_INSERT_ID()", Long.class);

            db.update(
                    "INSERT INTO test_questions(test_id,question_id,marks) " +
                    "VALUES(?,?,?)",
                    testId, questionId, q.marks);
        }

        List<Map<String,Object>> classes =
                db.queryForList(
                        "SELECT class_id FROM teacher_classes " +
                        "WHERE teacher_id=?", teacherId);

        for (Map<String,Object> row : classes) {
            db.update(
                    "INSERT IGNORE INTO test_assignments(test_id,class_id) " +
                    "VALUES(?,?)",
                    testId, row.get("class_id"));
        }

        return Map.of(
                "message", "Test created successfully",
                "testId", testId,
                "totalMarks", totalMarks);
    }

    // TEACHER: Pending test reviews

    @GetMapping("/teacher/reviews")
    public List<Map<String,Object>> pendingReviews(
            HttpSession session) {

        Long teacherId = getTeacherId(session);

        return db.queryForList(
                "SELECT ta.id AS attempt_id, " +
                "u.full_name AS student_name, " +
                "te.title AS test_title, s.name AS subject_name, " +
                "ta.score, ta.total_marks, ta.percentage " +
                "FROM test_attempts ta " +
                "JOIN tests te ON te.id=ta.test_id " +
                "JOIN subjects s ON s.id=te.subject_id " +
                "JOIN students st ON st.id=ta.student_id " +
                "JOIN users u ON u.id=st.user_id " +
                "JOIN teacher_subjects ts ON ts.subject_id=te.subject_id " +
                "AND ts.teacher_id=? " +
                "JOIN class_students cs ON cs.student_id=st.id " +
                "JOIN teacher_classes tc ON tc.class_id=cs.class_id " +
                "AND tc.teacher_id=? " +
                "WHERE ta.status='PENDING_REVIEW'",
                teacherId, teacherId);
    }

    // TEACHER: Approve test and provide feedback

    @PostMapping("/teacher/reviews/{attemptId}/approve")
    public Map<String,Object> approveTest(
            @PathVariable Long attemptId,
            @RequestBody ReviewRequest req,
            HttpSession session) {

        Long teacherId = getTeacherId(session);

        Integer allowed = db.queryForObject(
                "SELECT COUNT(*) FROM test_attempts ta " +
                "JOIN tests te ON te.id=ta.test_id " +
                "JOIN teacher_subjects ts ON ts.subject_id=te.subject_id " +
                "AND ts.teacher_id=? " +
                "JOIN class_students cs ON cs.student_id=ta.student_id " +
                "JOIN teacher_classes tc ON tc.class_id=cs.class_id " +
                "AND tc.teacher_id=? " +
                "WHERE ta.id=? AND ta.status='PENDING_REVIEW'",
                Integer.class, teacherId, teacherId, attemptId);

        if (allowed == null || allowed == 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You cannot approve this test.");
        }

        if (req.feedback == null || req.feedback.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please enter feedback.");
        }

        db.update(
                "UPDATE test_attempts SET status='APPROVED' " +
                "WHERE id=?", attemptId);

        db.update(
                "INSERT INTO teacher_feedback" +
                "(attempt_id,teacher_id,feedback) VALUES(?,?,?)",
                attemptId, teacherId, req.feedback.trim());

        return Map.of(
                "message", "Test approved and feedback shared.");
    }

    // TEACHER: Upload PDF notes

    @PostMapping(value="/teacher/materials",
            consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> uploadNotes(
            @RequestParam String title,
            @RequestParam Long subjectId,
            @RequestParam(required=false) String chapter,
            @RequestParam(required=false) String description,
            @RequestParam("file") MultipartFile file,
            HttpSession session) throws Exception {

        Long teacherId = getTeacherId(session);
        checkSubject(teacherId, subjectId);

        if (file.isEmpty() ||
                file.getSize() > 15L * 1024 * 1024) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Upload a PDF up to 15 MB.");
        }

        String original = StringUtils.cleanPath(
                Objects.requireNonNullElse(
                        file.getOriginalFilename(), "notes.pdf"));

        if (!original.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || !"application/pdf".equalsIgnoreCase(
                        file.getContentType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only PDF files are allowed.");
        }

        Files.createDirectories(UPLOAD_DIR);

        String stored = UUID.randomUUID() + ".pdf";

        Files.copy(file.getInputStream(),
                UPLOAD_DIR.resolve(stored),
                StandardCopyOption.REPLACE_EXISTING);

        db.update(
                "INSERT INTO study_materials" +
                "(teacher_id,subject_id,title,chapter,description," +
                "stored_filename,original_filename) " +
                "VALUES(?,?,?,?,?,?,?)",
                teacherId, subjectId, title, chapter,
                description, stored, original);

        return Map.of("message", "Notes uploaded successfully.");
    }

    // STUDENT: View assigned teachers

    @GetMapping("/student/teachers")
    public List<Map<String,Object>> studentTeachers(
            HttpSession session) {

        Long studentId = getStudentId(session);

        return db.queryForList(
                "SELECT DISTINCT s.name AS subject_name, " +
                "u.full_name AS teacher_name " +
                "FROM class_students cs " +
                "JOIN teacher_classes tc ON tc.class_id=cs.class_id " +
                "JOIN teachers t ON t.id=tc.teacher_id " +
                "JOIN users u ON u.id=t.user_id " +
                "JOIN teacher_subjects ts ON ts.teacher_id=t.id " +
                "JOIN subjects s ON s.id=ts.subject_id " +
                "WHERE cs.student_id=? ORDER BY s.name",
                studentId);
    }

    // STUDENT: AI learning video suggestions

    @GetMapping("/student/videos")
    public List<Map<String,Object>> learningVideos(
            HttpSession session) {

        Long studentId = getStudentId(session);

        List<Map<String,Object>> gaps = db.queryForList(
                "SELECT DISTINCT s.name AS subject_name, " +
                "tp.name AS topic_name, c.name AS concept_name " +
                "FROM learning_gaps lg " +
                "JOIN concepts c ON c.id=lg.concept_id " +
                "JOIN topics tp ON tp.id=c.topic_id " +
                "JOIN chapters ch ON ch.id=tp.chapter_id " +
                "JOIN subjects s ON s.id=ch.subject_id " +
                "WHERE lg.student_id=? AND lg.status='ACTIVE'",
                studentId);

        for (Map<String,Object> gap : gaps) {
            String query = gap.get("subject_name") + " " +
                    gap.get("topic_name") + " " +
                    gap.get("concept_name") +
                    " lesson for students";

            gap.put("youtubeUrl",
                    "https://www.youtube.com/results?search_query=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8));
        }

        return gaps;
    }


    // STUDENT: View notes uploaded for the student's assigned class and subjects

    @GetMapping("/student/materials")
    public List<Map<String,Object>> studentMaterials(HttpSession session) {
        Long studentId = getStudentId(session);
        return db.queryForList(
                "SELECT DISTINCT sm.id, sm.title, sm.chapter, sm.description, " +
                "sm.original_filename, sm.created_at, sub.name AS subject_name, " +
                "u.full_name AS teacher_name " +
                "FROM study_materials sm " +
                "JOIN subjects sub ON sub.id=sm.subject_id " +
                "JOIN teachers tr ON tr.id=sm.teacher_id " +
                "JOIN users u ON u.id=tr.user_id " +
                "JOIN teacher_subjects ts ON ts.teacher_id=tr.id AND ts.subject_id=sm.subject_id " +
                "JOIN teacher_classes tc ON tc.teacher_id=tr.id " +
                "JOIN class_students cs ON cs.class_id=tc.class_id " +
                "WHERE cs.student_id=? ORDER BY sm.created_at DESC, sm.id DESC",
                studentId);
    }

    // STUDENT: Securely download a note available to this student

    @GetMapping("/student/materials/{materialId}/download")
    public ResponseEntity<byte[]> downloadStudentMaterial(
            @PathVariable Long materialId, HttpSession session) throws Exception {
        Long studentId = getStudentId(session);
        List<Map<String,Object>> rows = db.queryForList(
                "SELECT sm.stored_filename, sm.original_filename " +
                "FROM study_materials sm " +
                "JOIN teacher_subjects ts ON ts.teacher_id=sm.teacher_id AND ts.subject_id=sm.subject_id " +
                "JOIN teacher_classes tc ON tc.teacher_id=sm.teacher_id " +
                "JOIN class_students cs ON cs.class_id=tc.class_id " +
                "WHERE sm.id=? AND cs.student_id=? LIMIT 1",
                materialId, studentId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study material not found.");
        }
        String stored = String.valueOf(rows.get(0).get("stored_filename"));
        Path filePath = UPLOAD_DIR.resolve(stored).normalize();
        if (!filePath.startsWith(UPLOAD_DIR) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The uploaded PDF is unavailable.");
        }
        byte[] bytes = Files.readAllBytes(filePath);
        String filename = String.valueOf(rows.get(0).get("original_filename"))
                .replaceAll("[\\r\\n\"\\\\]", "_");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }

    // STUDENT: Feedback shared by teachers after approving test attempts

    @GetMapping("/student/feedback")
    public List<Map<String,Object>> studentFeedback(HttpSession session) {
        Long studentId = getStudentId(session);
        return db.queryForList(
                "SELECT tf.id, tf.feedback, tf.created_at, " +
                "ta.score, ta.total_marks, ta.percentage, " +
                "te.title AS test_title, sub.name AS subject_name, " +
                "u.full_name AS teacher_name " +
                "FROM teacher_feedback tf " +
                "JOIN test_attempts ta ON ta.id=tf.attempt_id " +
                "JOIN tests te ON te.id=ta.test_id " +
                "JOIN subjects sub ON sub.id=te.subject_id " +
                "JOIN teachers tr ON tr.id=tf.teacher_id " +
                "JOIN users u ON u.id=tr.user_id " +
                "WHERE ta.student_id=? AND ta.status='APPROVED' " +
                "ORDER BY tf.created_at DESC, tf.id DESC",
                studentId);
    }

    // PARENT: Feedback for every child actively linked to this parent account

    @GetMapping("/parent/feedback")
    public List<Map<String,Object>> parentFeedback(HttpSession session) {
        Long parentId = getParentId(session);
        return db.queryForList(
                "SELECT tf.id, tf.feedback, tf.created_at, " +
                "ta.score, ta.total_marks, ta.percentage, " +
                "te.title AS test_title, sub.name AS subject_name, " +
                "u.full_name AS teacher_name, child.full_name AS student_name, " +
                "st.student_id_code " +
                "FROM parent_student_links psl " +
                "JOIN students st ON st.id=psl.student_id " +
                "JOIN users child ON child.id=st.user_id " +
                "JOIN test_attempts ta ON ta.student_id=st.id " +
                "JOIN teacher_feedback tf ON tf.attempt_id=ta.id " +
                "JOIN tests te ON te.id=ta.test_id " +
                "JOIN subjects sub ON sub.id=te.subject_id " +
                "JOIN teachers tr ON tr.id=tf.teacher_id " +
                "JOIN users u ON u.id=tr.user_id " +
                "WHERE psl.parent_id=? AND psl.status='ACTIVE' " +
                "AND ta.status='APPROVED' " +
                "ORDER BY tf.created_at DESC, tf.id DESC",
                parentId);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static class ManualTestRequest {
        public String title;
        public Long subjectId;
        public Long chapterId;
        public int durationMinutes = 20;
        public List<ManualQuestion> questions;
    }

    public static class ManualQuestion {
        public String text;
        public String a, b, c, d;
        public String correct;
        public String explanation;
        public int marks = 1;
    }

    public static class ReviewRequest {
        public String feedback;
    }
}