package com.gapfinder;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private static final Set<String> ROLES = Set.of("STUDENT", "TEACHER", "PARENT");

    public AuthController(JdbcTemplate db) {
        this.db = db;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterPayload payload, HttpSession session) {
        if (payload.name == null || payload.name.trim().isEmpty() ||
            payload.email == null || !payload.email.contains("@") ||
            payload.password == null || payload.password.length() < 6 ||
            payload.role == null || !ROLES.contains(payload.role.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please provide full name, valid email, password (min 6 chars), and valid role.");
        }

        String email = payload.email.trim().toLowerCase();
        String role = payload.role.toUpperCase();

        Number exists = db.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Number.class, email);
        if (exists != null && exists.intValue() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email address is already registered.");
        }

        // Insert into users
        db.update("INSERT INTO users(full_name, email, password_hash, role) VALUES(?, ?, ?, ?)",
                payload.name.trim(), email, encoder.encode(payload.password), role);
        Long userId = db.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);

        if ("STUDENT".equals(role)) {
            String studentCode = "STU-" + (1000 + (int)(Math.random() * 9000));
            String linkCode = "LINK-" + payload.name.replaceAll("\s+", "").toUpperCase() + (10 + (int)(Math.random() * 90));
            String school = payload.school != null && !payload.school.isBlank() ? payload.school.trim() : "Greenwood High School";
            String grade = payload.grade != null && !payload.grade.isBlank() ? payload.grade.trim() : "Grade 10";
            String section = payload.section != null && !payload.section.isBlank() ? payload.section.trim() : "A";

            db.update("INSERT INTO students(user_id, student_id_code, school, grade, section, link_code) VALUES(?, ?, ?, ?, ?, ?)",
                    userId, studentCode, school, grade, section, linkCode);
            Long studentId = db.queryForObject("SELECT id FROM students WHERE user_id = ?", Long.class, userId);

            // Assign to default class
            Long classId = db.queryForObject("SELECT id FROM classes WHERE name = 'Grade 10 - Section A' LIMIT 1", Long.class);
            if (classId != null) {
                db.update("INSERT IGNORE INTO class_students(class_id, student_id) VALUES(?, ?)", classId, studentId);
            }

            // Award Welcome achievement
            db.update("INSERT INTO achievements(student_id, badge_name, badge_desc, icon) VALUES(?, 'Welcome Explorer', 'Registered an official GAP FINDER student account', 'user-check')", studentId);

        } else if ("PARENT".equals(role)) {
            String phone = payload.phone != null ? payload.phone.trim() : "";
            db.update("INSERT INTO parents(user_id, phone) VALUES(?, ?)", userId, phone);
            Long parentId = db.queryForObject("SELECT id FROM parents WHERE user_id = ?", Long.class, userId);

            // If parent provided child link code during registration
            if (payload.childLinkCode != null && !payload.childLinkCode.isBlank()) {
                var stuList = db.queryForList("SELECT id FROM students WHERE link_code = ?", payload.childLinkCode.trim());
                if (!stuList.isEmpty()) {
                    Long studentId = ((Number) stuList.get(0).get("id")).longValue();
                    db.update("INSERT IGNORE INTO parent_student_links(parent_id, student_id, status) VALUES(?, ?, 'ACTIVE')",
                            parentId, studentId);
                }
            }

        } else if ("TEACHER".equals(role)) {
            String teacherCode = "TCH-" + (500 + (int)(Math.random() * 500));
            String school = payload.school != null && !payload.school.isBlank() ? payload.school.trim() : "Greenwood High School";
            String subject = payload.subject != null && !payload.subject.isBlank() ? payload.subject.trim() : "General Science & Mathematics";

            db.update("INSERT INTO teachers(user_id, teacher_id_code, school, subject) VALUES(?, ?, ?, ?)",
                    userId, teacherCode, school, subject);
            Long teacherId = db.queryForObject("SELECT id FROM teachers WHERE user_id = ?", Long.class, userId);
            // Convert the teacher's selected subject text into explicit subject assignments.
            for (Map<String,Object> subjectRow : db.queryForList("SELECT id,name FROM subjects")) {
                String subjectName = String.valueOf(subjectRow.get("name"));
                boolean matches = subject.toLowerCase(Locale.ROOT).contains(subjectName.toLowerCase(Locale.ROOT));
                if (!matches && subject.toLowerCase(Locale.ROOT).contains("science") && (subjectName.equalsIgnoreCase("Physics") || subjectName.equalsIgnoreCase("Chemistry"))) matches = true;
                if (matches) db.update("INSERT IGNORE INTO teacher_subjects(teacher_id,subject_id) VALUES(?,?)", teacherId, subjectRow.get("id"));
            }

            // Assign teacher to default class
            Long classId = db.queryForObject("SELECT id FROM classes WHERE name = 'Grade 10 - Section A' LIMIT 1", Long.class);
            if (classId != null) {
                db.update("INSERT IGNORE INTO teacher_classes(teacher_id, class_id) VALUES(?, ?)", teacherId, classId);
            }
        }

        session.setAttribute("userId", userId);
        session.setAttribute("role", role);

        return Map.of("message", "Registration successful", "user", getUserProfile(userId));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginPayload payload, HttpSession session) {
        if (payload.email == null || payload.password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required.");
        }

        String email = payload.email.trim().toLowerCase();
        var rows = db.query("SELECT id, password_hash, role FROM users WHERE email = ?",
                (rs, rowNum) -> Map.of("id", rs.getLong("id"), "hash", rs.getString("password_hash"), "role", rs.getString("role")),
                email);

        if (rows.isEmpty() || !encoder.matches(payload.password, (String) rows.get(0).get("hash"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        Long userId = (Long) rows.get(0).get("id");
        String role = (String) rows.get(0).get("role");

        session.setAttribute("userId", userId);
        session.setAttribute("role", role);

        return Map.of("message", "Login successful", "user", getUserProfile(userId));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired or not logged in.");
        }
        return getUserProfile((Long) uid);
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpSession session) {
        session.invalidate();
        return Map.of("message", "Logged out successfully");
    }

    private Map<String, Object> getUserProfile(Long userId) {
        Map<String, Object> user = db.queryForMap(
                "SELECT id, full_name AS name, email, role, created_at FROM users WHERE id = ?", userId);
        String role = (String) user.get("role");

        Map<String, Object> profile = new HashMap<>(user);

        if ("STUDENT".equals(role)) {
            var stuRows = db.queryForList("SELECT id, student_id_code, school, grade, section, link_code FROM students WHERE user_id = ?", userId);
            if (!stuRows.isEmpty()) {
                profile.putAll(stuRows.get(0));
            }
        } else if ("PARENT".equals(role)) {
            var parentRows = db.queryForList("SELECT id, phone FROM parents WHERE user_id = ?", userId);
            if (!parentRows.isEmpty()) {
                profile.putAll(parentRows.get(0));
                Long parentId = ((Number) parentRows.get(0).get("id")).longValue();
                var children = db.queryForList(
                        "SELECT s.id AS student_id, u.full_name AS child_name, s.grade, s.section, s.school " +
                        "FROM parent_student_links psl " +
                        "JOIN students s ON psl.student_id = s.id " +
                        "JOIN users u ON s.user_id = u.id " +
                        "WHERE psl.parent_id = ? AND psl.status = 'ACTIVE'", parentId);
                profile.put("children", children);
            }
        } else if ("TEACHER".equals(role)) {
            var tchRows = db.queryForList("SELECT id, teacher_id_code, school, subject FROM teachers WHERE user_id = ?", userId);
            if (!tchRows.isEmpty()) {
                profile.putAll(tchRows.get(0));
            }
        }

        return profile;
    }

    public static class RegisterPayload {
        public String name;
        public String email;
        public String password;
        public String role;
        public String school;
        public String grade;
        public String section;
        public String phone;
        public String subject;
        public String childLinkCode;
    }

    public static class LoginPayload {
        public String email;
        public String password;
    }
}
