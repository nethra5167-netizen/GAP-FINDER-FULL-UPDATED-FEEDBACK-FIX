package com.gapfinder;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {

    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DataInitializer(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void run(String... args) {
        try {
            initSampleUsersAndProfiles();
            initCurriculumAndQuestions();
            initTeacherSubjectAssignments();
            db.update("UPDATE test_attempts SET status='APPROVED' WHERE status='COMPLETED'");
            initDiagnosticTests();
            initStudentBaselineData();
            System.out.println("=== GAP FINDER DATA INITIALIZATION COMPLETED SUCCESSFULLY ===");
        } catch (Exception e) {
            System.err.println("DataInitializer notice: " + e.getMessage());
        }
    }

    private void initSampleUsersAndProfiles() {
        // 1. Create Class
        Integer classCount = db.queryForObject("SELECT COUNT(*) FROM classes WHERE name = 'Grade 10 - Section A'", Integer.class);
        if (classCount == null || classCount == 0) {
            db.update("INSERT INTO classes(name, grade, section) VALUES('Grade 10 - Section A', 'Grade 10', 'A')");
        }
        Long classId = db.queryForObject("SELECT id FROM classes WHERE name = 'Grade 10 - Section A' LIMIT 1", Long.class);

        // 2. Student: Aarav Sharma (student@gapfinder.com / Student@123)
        Integer studentCount = db.queryForObject("SELECT COUNT(*) FROM users WHERE email = 'student@gapfinder.com'", Integer.class);
        if (studentCount == null || studentCount == 0) {
            db.update("INSERT INTO users(full_name, email, password_hash, role) VALUES(?, ?, ?, ?)",
                    "Aarav Sharma", "student@gapfinder.com", encoder.encode("Student@123"), "STUDENT");
            Long userId = db.queryForObject("SELECT id FROM users WHERE email = 'student@gapfinder.com'", Long.class);
            db.update("INSERT INTO students(user_id, student_id_code, school, grade, section, link_code) VALUES(?, ?, ?, ?, ?, ?)",
                    userId, "STU-101", "Greenwood High School", "Grade 10", "A", "LINK-AARAV10");
            Long studentId = db.queryForObject("SELECT id FROM students WHERE user_id = ?", Long.class, userId);
            db.update("INSERT IGNORE INTO class_students(class_id, student_id) VALUES(?, ?)", classId, studentId);
        }

        // Additional student: Priya Patel (priya@gapfinder.com / Student@123)
        Integer priyaCount = db.queryForObject("SELECT COUNT(*) FROM users WHERE email = 'priya@gapfinder.com'", Integer.class);
        if (priyaCount == null || priyaCount == 0) {
            db.update("INSERT INTO users(full_name, email, password_hash, role) VALUES(?, ?, ?, ?)",
                    "Priya Patel", "priya@gapfinder.com", encoder.encode("Student@123"), "STUDENT");
            Long userId = db.queryForObject("SELECT id FROM users WHERE email = 'priya@gapfinder.com'", Long.class);
            db.update("INSERT INTO students(user_id, student_id_code, school, grade, section, link_code) VALUES(?, ?, ?, ?, ?, ?)",
                    userId, "STU-102", "Greenwood High School", "Grade 10", "A", "LINK-PRIYA10");
            Long studentId = db.queryForObject("SELECT id FROM students WHERE user_id = ?", Long.class, userId);
            db.update("INSERT IGNORE INTO class_students(class_id, student_id) VALUES(?, ?)", classId, studentId);
        }

        // 3. Parent: Rajesh Sharma (parent@gapfinder.com / Parent@123)
        Integer parentCount = db.queryForObject("SELECT COUNT(*) FROM users WHERE email = 'parent@gapfinder.com'", Integer.class);
        if (parentCount == null || parentCount == 0) {
            db.update("INSERT INTO users(full_name, email, password_hash, role) VALUES(?, ?, ?, ?)",
                    "Rajesh Sharma", "parent@gapfinder.com", encoder.encode("Parent@123"), "PARENT");
            Long userId = db.queryForObject("SELECT id FROM users WHERE email = 'parent@gapfinder.com'", Long.class);
            db.update("INSERT INTO parents(user_id, phone) VALUES(?, ?)", userId, "+91 98765 43210");
            Long parentId = db.queryForObject("SELECT id FROM parents WHERE user_id = ?", Long.class, userId);

            Long studentUserId = db.queryForObject("SELECT id FROM users WHERE email = 'student@gapfinder.com'", Long.class);
            Long studentId = db.queryForObject("SELECT id FROM students WHERE user_id = ?", Long.class, studentUserId);
            db.update("INSERT IGNORE INTO parent_student_links(parent_id, student_id, status) VALUES(?, ?, 'ACTIVE')", parentId, studentId);
        }

        // 4. Teacher: Dr. Sunita Rao (teacher@gapfinder.com / Teacher@123)
        Integer teacherCount = db.queryForObject("SELECT COUNT(*) FROM users WHERE email = 'teacher@gapfinder.com'", Integer.class);
        if (teacherCount == null || teacherCount == 0) {
            db.update("INSERT INTO users(full_name, email, password_hash, role) VALUES(?, ?, ?, ?)",
                    "Dr. Sunita Rao", "teacher@gapfinder.com", encoder.encode("Teacher@123"), "TEACHER");
            Long userId = db.queryForObject("SELECT id FROM users WHERE email = 'teacher@gapfinder.com'", Long.class);
            db.update("INSERT INTO teachers(user_id, teacher_id_code, school, subject) VALUES(?, ?, ?, ?)",
                    userId, "TCH-501", "Greenwood High School", "Mathematics & Computer Science");
            Long teacherId = db.queryForObject("SELECT id FROM teachers WHERE user_id = ?", Long.class, userId);
            db.update("INSERT IGNORE INTO teacher_classes(teacher_id, class_id) VALUES(?, ?)", teacherId, classId);

            db.update("INSERT INTO announcements(teacher_id, class_id, title, content) VALUES(?, ?, ?, ?)",
                    teacherId, classId,
                    "Welcome to Term Diagnostic Assessments!",
                    "Please complete the Algebra Diagnostic and Java Foundations tests by Friday. Our Gap Finder AI will analyze your submissions and build your personalized revision plan.");
        }
    }


    private void initTeacherSubjectAssignments() {
        // The sample teacher profile says "Mathematics & Computer Science".
        // Match those subject names and persist the explicit teacher-subject links.
        List<Long> teacherIds = db.queryForList("SELECT id FROM teachers", Long.class);
        for (Long teacherId : teacherIds) {
            String assignedText = db.queryForObject("SELECT subject FROM teachers WHERE id=?", String.class, teacherId);
            if (assignedText == null) continue;
            for (MapSubject subject : List.of(new MapSubject("Mathematics", "MATH"), new MapSubject("Physics", "PHYS"),
                    new MapSubject("Chemistry", "CHEM"), new MapSubject("Computer Science", "CS"))) {
                if (assignedText.toLowerCase().contains(subject.name.toLowerCase())) {
                    db.update("INSERT IGNORE INTO teacher_subjects(teacher_id,subject_id) SELECT ?,id FROM subjects WHERE code=?", teacherId, subject.code);
                }
            }
        }
    }

    private static class MapSubject { final String name; final String code; MapSubject(String name,String code){this.name=name;this.code=code;} }

    private void initCurriculumAndQuestions() {
        // Subjects
        insertSubjectIfNotExists("Mathematics", "MATH", "calculator", "#2563eb");
        insertSubjectIfNotExists("Physics", "PHYS", "atom", "#0284c7");
        insertSubjectIfNotExists("Chemistry", "CHEM", "flask", "#059669");
        insertSubjectIfNotExists("Computer Science", "CS", "laptop-code", "#7c3aed");

        Long mathId = getSubjectId("MATH");
        Long physId = getSubjectId("PHYS");
        Long chemId = getSubjectId("CHEM");
        Long csId = getSubjectId("CS");

        // Mathematics Curriculum
        Long mathCh1 = insertChapterIfNotExists(mathId, "Algebra & Equations", 1);
        Long mathTop1 = insertTopicIfNotExists(mathCh1, "Linear Equations in One Variable");
        Long cLinearIso = insertConceptIfNotExists(mathTop1, "Isolating Variables in Multi-Step Equations", "Balancing equations and isolating variables across equality bounds.");
        Long cLinearSimp = insertConceptIfNotExists(mathTop1, "Simplification & Combining Like Terms", "Simplifying polynomials and collecting like coefficients.");
        Long cLinearWord = insertConceptIfNotExists(mathTop1, "Word Problems & Translation", "Translating real-world statements into mathematical equations.");

        Long mathCh2 = insertChapterIfNotExists(mathId, "Geometry & Trigonometry", 2);
        Long mathTop2 = insertTopicIfNotExists(mathCh2, "Right Angled Triangles & Trigonometry");
        Long cTrigPyth = insertConceptIfNotExists(mathTop2, "Pythagorean Theorem Application", "Calculating hypotenuse and leg lengths in right triangles.");
        Long cTrigRatios = insertConceptIfNotExists(mathTop2, "Basic Trigonometric Ratios", "Understanding Sin, Cos, and Tan ratios.");

        // Physics Curriculum
        Long physCh1 = insertChapterIfNotExists(physId, "Classical Mechanics & Dynamics", 1);
        Long physTop1 = insertTopicIfNotExists(physCh1, "Newton's Laws of Motion");
        Long cNewt1 = insertConceptIfNotExists(physTop1, "First Law: Inertia & Equilibrium", "Objects remain at rest or uniform motion unless acted upon by a net force.");
        Long cNewt2 = insertConceptIfNotExists(physTop1, "Second Law: Force and Acceleration (F=ma)", "Relationship between net force, mass, and acceleration.");
        Long cNewt3 = insertConceptIfNotExists(physTop1, "Third Law: Action & Reaction Pairs", "Every action force has an equal and opposite reaction force.");

        // Chemistry Curriculum
        Long chemCh1 = insertChapterIfNotExists(chemId, "Atomic Structure & Chemical Bonding", 1);
        Long chemTop1 = insertTopicIfNotExists(chemCh1, "Chemical Bonding Principles");
        Long cChemIonic = insertConceptIfNotExists(chemTop1, "Ionic Bonding & Electron Transfer", "Formation of ions through complete transfer of valence electrons.");
        Long cChemCovalent = insertConceptIfNotExists(chemTop1, "Covalent Bonding & Electron Sharing", "Sharing of valence electron pairs between nonmetals.");

        // Computer Science Curriculum
        Long csCh1 = insertChapterIfNotExists(csId, "Object-Oriented Programming in Java", 1);
        Long csTop1 = insertTopicIfNotExists(csCh1, "Classes, Objects & Constructors");
        Long cCsObj = insertConceptIfNotExists(csTop1, "Object Instantiation & Memory", "How the 'new' operator allocates heap memory for class instances.");
        Long cCsCons = insertConceptIfNotExists(csTop1, "Constructors & Initialization", "Constructors share the class name and initialize object state.");
        Long csTop2 = insertTopicIfNotExists(csCh1, "Inheritance & Polymorphism");
        Long cCsInh = insertConceptIfNotExists(csTop2, "Inheritance with 'extends' Keyword", "Subclassing and code reuse in Java.");
        Long cCsOver = insertConceptIfNotExists(csTop2, "Method Overriding vs Overloading", "Runtime polymorphism vs compile-time method resolution.");

        // Questions Pool
        // Math - Isolating Variables
        insertQuestionIfNotExists(cLinearIso,
                "Solve for x: 3x + 7 = 22",
                "x = 3", "x = 5", "x = 7", "x = 9",
                'B', "Subtract 7 from both sides to get 3x = 15, then divide both sides by 3 to get x = 5.");
        insertQuestionIfNotExists(cLinearIso,
                "Solve for y: 4(y - 2) = 2(y + 6)",
                "y = 8", "y = 10", "y = 6", "y = 12",
                'B', "Expand both sides: 4y - 8 = 2y + 12. Subtract 2y: 2y - 8 = 12. Add 8: 2y = 20. Divide by 2: y = 10.");
        insertQuestionIfNotExists(cLinearIso,
                "Solve for m: 5m - 15 = 2m + 9",
                "m = 6", "m = 8", "m = 7", "m = 9",
                'B', "Subtract 2m: 3m - 15 = 9. Add 15: 3m = 24. Divide by 3: m = 8.");

        // Math - Simplification
        insertQuestionIfNotExists(cLinearSimp,
                "Simplify the algebraic expression: 4x + 3y - 2x + 5y",
                "6x + 8y", "2x + 8y", "2x - 2y", "7xy",
                'B', "Group like terms: (4x - 2x) + (3y + 5y) = 2x + 8y.");
        insertQuestionIfNotExists(cLinearSimp,
                "What is the simplified form of 2(3a + 4) - 5a?",
                "a + 8", "11a + 8", "a + 4", "6a - 1",
                'A', "Distribute the 2: 6a + 8 - 5a. Combine like terms: (6a - 5a) + 8 = a + 8.");

        // Math - Word Problems
        insertQuestionIfNotExists(cLinearWord,
                "Ravi is 4 years older than twice his sister Priya's age. If Ravi is 18 years old, which equation correctly represents Priya's age p?",
                "2p - 4 = 18", "2p + 4 = 18", "4p + 2 = 18", "p/2 + 4 = 18",
                'B', "'Twice Priya\'s age' is 2p. '4 years older' means add 4: 2p + 4 = 18.");
        insertQuestionIfNotExists(cLinearWord,
                "The perimeter of a rectangle is 48 cm. The length is 6 cm longer than the width w. What is the equation to find w?",
                "2(w + w + 6) = 48", "w(w + 6) = 48", "2w + 6 = 48", "4w + 6 = 48",
                'A', "Perimeter = 2*(length + width). Since length = w + 6, Perimeter = 2(w + 6 + w) = 2(2w + 6) = 48.");

        // Math - Trigonometry
        insertQuestionIfNotExists(cTrigPyth,
                "In a right triangle with legs of length 6 cm and 8 cm, what is the length of the hypotenuse c?",
                "9 cm", "10 cm", "12 cm", "14 cm",
                'B', "By Pythagorean theorem: c^2 = 6^2 + 8^2 = 36 + 64 = 100. Thus, c = sqrt(100) = 10 cm.");
        insertQuestionIfNotExists(cTrigRatios,
                "In a right triangle, what is the definition of tan(theta)?",
                "Adjacent / Hypotenuse", "Opposite / Adjacent", "Opposite / Hypotenuse", "Hypotenuse / Opposite",
                'B', "By trigonometric definition, tangent of an angle equals Opposite side divided by Adjacent side.");

        // Physics Questions
        insertQuestionIfNotExists(cNewt1,
                "According to Newton's First Law of Motion, what causes an object in motion to change its velocity?",
                "Internal kinetic forces", "An unbalanced external net force", "The object's momentum", "Constant atmospheric pressure",
                'B', "Newton's First Law states that an object continues in its state of rest or uniform motion unless acted upon by a net external unbalanced force.");
        insertQuestionIfNotExists(cNewt2,
                "A force of 30 N is applied to an object with a mass of 6 kg. What is the acceleration produced?",
                "180 m/s^2", "5 m/s^2", "24 m/s^2", "0.2 m/s^2",
                'B', "Using F = m * a, a = F / m = 30 N / 6 kg = 5 m/s^2.");
        insertQuestionIfNotExists(cNewt2,
                "If the mass of an object is doubled while the net force remains constant, how does the acceleration change?",
                "It quadruples", "It is halved", "It doubles", "It remains constant",
                'B', "Since a = F/m, acceleration is inversely proportional to mass. Doubling mass halves acceleration.");
        insertQuestionIfNotExists(cNewt3,
                "When a rocket engine fires hot gases downward, what force propels the rocket upward according to Newton's Third Law?",
                "Gravitational assist", "Reaction force exerted by the escaping gas on the rocket", "Atmospheric air push", "Centrifugal repulsion",
                'B', "Newton's Third Law: The downward action force of the gases produces an equal and opposite upward reaction force on the rocket.");

        // Chemistry Questions
        insertQuestionIfNotExists(cChemIonic,
                "An ionic bond is primarily formed between which types of elements?",
                "Two nonmetal atoms", "A metal and a nonmetal atom", "Two noble gas atoms", "Two identical halogen atoms",
                'B', "Ionic bonding occurs when a metal transfers electrons to a nonmetal, forming oppositely charged cations and anions.");
        insertQuestionIfNotExists(cChemCovalent,
                "What defines a covalent bond between two atoms?",
                "Transfer of protons", "Sharing of valence electron pairs", "Electrostatic attraction between opposite ions", "Delocalized sea of electrons",
                'B', "A covalent bond is formed by the mutual sharing of one or more pairs of valence electrons between atoms.");

        // CS Questions
        insertQuestionIfNotExists(cCsObj,
                "Which keyword is used in Java to dynamically allocate memory and instantiate an object?",
                "class", "new", "malloc", "this",
                'B', "The 'new' operator instantiates a class by allocating memory for a new object in the JVM heap.");
        insertQuestionIfNotExists(cCsCons,
                "What is a defining rule of constructors in Java?",
                "They must return void", "They have the exact same name as the class and no return type", "They must always be declared private", "They must have the 'static' modifier",
                'B', "A constructor in Java must have the exact same name as the enclosing class and must not specify any return type.");
        insertQuestionIfNotExists(cCsInh,
                "Which keyword allows a Java class to inherit variables and methods from another class?",
                "implements", "extends", "inherits", "super",
                'B', "The 'extends' keyword is used in class declarations to inherit from a superclass.");
        insertQuestionIfNotExists(cCsOver,
                "When a subclass provides a specific implementation of a method already defined in its parent class, this is called:",
                "Method Overloading", "Method Overriding", "Method Shadowing", "Method Hiding",
                'B', "Method Overriding occurs when a subclass provides its own specific implementation for a method declared in its superclass.");
    }

    private void initDiagnosticTests() {
        Long mathId = getSubjectId("MATH");
        Long physId = getSubjectId("PHYS");
        Long chemId = getSubjectId("CHEM");
        Long csId = getSubjectId("CS");
        Long classId = db.queryForObject("SELECT id FROM classes WHERE name = 'Grade 10 - Section A' LIMIT 1", Long.class);

        Long mathTestId = insertTestIfNotExists("Mathematics Diagnostic: Algebra & Linear Equations", mathId, 25, 60, 24);
        attachQuestionsToTest(mathTestId, "Mathematics");
        assignTestToClassIfNotExists(mathTestId, classId);

        Long physTestId = insertTestIfNotExists("Physics Fundamentals: Mechanics & Newton's Laws", physId, 20, 40, 16);
        attachQuestionsToTest(physTestId, "Physics");
        assignTestToClassIfNotExists(physTestId, classId);

        Long chemTestId = insertTestIfNotExists("Chemistry Diagnostic: Chemical Bonding & Structure", chemId, 20, 20, 8);
        attachQuestionsToTest(chemTestId, "Chemistry");
        assignTestToClassIfNotExists(chemTestId, classId);

        Long csTestId = insertTestIfNotExists("Computer Science: Java & OOP Foundations", csId, 20, 40, 16);
        attachQuestionsToTest(csTestId, "Computer Science");
        assignTestToClassIfNotExists(csTestId, classId);
    }

    private void initStudentBaselineData() {
        Long studentUserId = db.queryForObject("SELECT id FROM users WHERE email = 'student@gapfinder.com'", Long.class);
        Long studentId = db.queryForObject("SELECT id FROM students WHERE user_id = ?", Long.class, studentUserId);

        Integer attemptsCount = db.queryForObject("SELECT COUNT(*) FROM test_attempts WHERE student_id = ?", Integer.class, studentId);
        if (attemptsCount == null || attemptsCount == 0) {
            Long mathTestId = db.queryForObject("SELECT id FROM tests WHERE title LIKE 'Mathematics Diagnostic%' LIMIT 1", Long.class);

            db.update("INSERT INTO test_attempts(test_id, student_id, score, total_marks, percentage, status, completed_at) VALUES(?, ?, 30, 60, 50.0, 'COMPLETED', NOW() - INTERVAL 1 DAY)",
                    mathTestId, studentId);

            Long cIso = db.queryForObject("SELECT id FROM concepts WHERE name LIKE 'Isolating Variables%' LIMIT 1", Long.class);
            db.update("INSERT INTO concept_performance(student_id, concept_id, total_attempts, correct_attempts, proficiency_level, last_evaluated_at) VALUES(?, ?, 3, 1, 'NEEDS_IMPROVEMENT', NOW())",
                    studentId, cIso);

            Long cWord = db.queryForObject("SELECT id FROM concepts WHERE name LIKE 'Word Problems%' LIMIT 1", Long.class);
            db.update("INSERT INTO concept_performance(student_id, concept_id, total_attempts, correct_attempts, proficiency_level, last_evaluated_at) VALUES(?, ?, 2, 0, 'NEEDS_IMPROVEMENT', NOW())",
                    studentId, cWord);

            Long cSimp = db.queryForObject("SELECT id FROM concepts WHERE name LIKE 'Simplification%' LIMIT 1", Long.class);
            db.update("INSERT INTO concept_performance(student_id, concept_id, total_attempts, correct_attempts, proficiency_level, last_evaluated_at) VALUES(?, ?, 2, 2, 'PROFICIENT', NOW())",
                    studentId, cSimp);

            db.update("INSERT INTO learning_gaps(student_id, concept_id, severity, reason, recommendation, status) VALUES(?, ?, 'HIGH', ?, ?, 'ACTIVE')",
                    studentId, cIso,
                    "Accuracy is 33% across 3 attempts. Repeated error when isolating variables across equality signs in multi-step equations.",
                    "Revise step-by-step variable transposition rules. Complete 5 practice questions on two-step linear equations before re-testing.");

            db.update("INSERT INTO learning_gaps(student_id, concept_id, severity, reason, recommendation, status) VALUES(?, ?, 'MEDIUM', ?, ?, 'ACTIVE')",
                    studentId, cWord,
                    "Accuracy is 0% across 2 attempts. Student struggles with translating verbal problem descriptions into algebraic statements.",
                    "Review algebraic translation keywords (e.g., 'more than', 'times', 'less than'). Solve 3 guided word-problem drills.");

            db.update("INSERT INTO achievements(student_id, badge_name, badge_desc, icon) VALUES(?, 'Diagnostic Pioneer', 'Completed first comprehensive diagnostic assessment', 'compass')", studentId);
            db.update("INSERT INTO achievements(student_id, badge_name, badge_desc, icon) VALUES(?, 'Math Explorer', 'Demonstrated 100% mastery in Algebraic Simplification', 'award')", studentId);
            db.update("INSERT INTO achievements(student_id, badge_name, badge_desc, icon) VALUES(?, 'Active Learner', 'Joined the Gap Finder learning community', 'star')", studentId);
        }
    }

    private void insertSubjectIfNotExists(String name, String code, String icon, String color) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM subjects WHERE code = ?", Integer.class, code);
        if (count == null || count == 0) {
            db.update("INSERT INTO subjects(name, code, icon, color) VALUES(?, ?, ?, ?)", name, code, icon, color);
        }
    }

    private Long getSubjectId(String code) {
        return db.queryForObject("SELECT id FROM subjects WHERE code = ?", Long.class, code);
    }

    private Long insertChapterIfNotExists(Long subjectId, String name, int order) {
        var list = db.queryForList("SELECT id FROM chapters WHERE subject_id = ? AND name = ?", subjectId, name);
        if (!list.isEmpty()) {
            return ((Number) list.get(0).get("id")).longValue();
        }
        db.update("INSERT INTO chapters(subject_id, name, chapter_order) VALUES(?, ?, ?)", subjectId, name, order);
        return db.queryForObject("SELECT id FROM chapters WHERE subject_id = ? AND name = ?", Long.class, subjectId, name);
    }

    private Long insertTopicIfNotExists(Long chapterId, String name) {
        var list = db.queryForList("SELECT id FROM topics WHERE chapter_id = ? AND name = ?", chapterId, name);
        if (!list.isEmpty()) {
            return ((Number) list.get(0).get("id")).longValue();
        }
        db.update("INSERT INTO topics(chapter_id, name) VALUES(?, ?)", chapterId, name);
        return db.queryForObject("SELECT id FROM topics WHERE chapter_id = ? AND name = ?", Long.class, chapterId, name);
    }

    private Long insertConceptIfNotExists(Long topicId, String name, String desc) {
        var list = db.queryForList("SELECT id FROM concepts WHERE topic_id = ? AND name = ?", topicId, name);
        if (!list.isEmpty()) {
            return ((Number) list.get(0).get("id")).longValue();
        }
        db.update("INSERT INTO concepts(topic_id, name, description) VALUES(?, ?, ?)", topicId, name, desc);
        return db.queryForObject("SELECT id FROM concepts WHERE topic_id = ? AND name = ?", Long.class, topicId, name);
    }

    private void insertQuestionIfNotExists(Long conceptId, String text, String a, String b, String c, String d, char correct, String expl) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM questions WHERE concept_id = ? AND question_text = ?", Integer.class, conceptId, text);
        if (count == null || count == 0) {
            db.update("INSERT INTO questions(concept_id, question_text, option_a, option_b, option_c, option_d, correct_option, explanation) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                    conceptId, text, a, b, c, d, String.valueOf(correct), expl);
        }
    }

    private Long insertTestIfNotExists(String title, Long subjectId, int duration, int totalMarks, int passingMarks) {
        var list = db.queryForList("SELECT id FROM tests WHERE title = ?", title);
        if (!list.isEmpty()) {
            return ((Number) list.get(0).get("id")).longValue();
        }
        db.update("INSERT INTO tests(title, subject_id, duration_minutes, total_marks, passing_marks, is_published) VALUES(?, ?, ?, ?, ?, TRUE)",
                title, subjectId, duration, totalMarks, passingMarks);
        return db.queryForObject("SELECT id FROM tests WHERE title = ?", Long.class, title);
    }

    private void attachQuestionsToTest(Long testId, String subjectName) {
        var questions = db.queryForList(
                "SELECT q.id FROM questions q " +
                "JOIN concepts c ON q.concept_id = c.id " +
                "JOIN topics t ON c.topic_id = t.id " +
                "JOIN chapters ch ON t.chapter_id = ch.id " +
                "JOIN subjects s ON ch.subject_id = s.id " +
                "WHERE s.name = ?", subjectName);
        for (var row : questions) {
            Long qId = ((Number) row.get("id")).longValue();
            db.update("INSERT IGNORE INTO test_questions(test_id, question_id, marks) VALUES(?, ?, 10)", testId, qId);
        }
    }

    private void assignTestToClassIfNotExists(Long testId, Long classId) {
        db.update("INSERT IGNORE INTO test_assignments(test_id, class_id) VALUES(?, ?)", testId, classId);
    }
}
