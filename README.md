# GAP FINDER - Educational Web Application

**Find Learning Gaps. Build Stronger Futures.**

An AI-powered diagnostic educational platform for **Students**, **Parents**, and **Teachers**.

---

## 🌟 Overview

GAP FINDER pinpoints exact conceptual misunderstandings across academic subjects. Rather than labeling a student as generally "weak" in a subject, GAP FINDER traces mistakes down to the exact **Subject &rarr; Chapter &rarr; Topic &rarr; Concept**, requiring cumulative assessment evidence before flagging a gap and providing personalized revision recommendations.

---

## 🛠️ Technology Stack

- **Backend**: Java 17, Spring Boot 3.3.5, Spring Security Crypto (BCrypt), Spring Data JDBC (`JdbcTemplate`), Maven
- **Database**: MySQL 8.x
- **Frontend**: Responsive HTML5, Modern CSS3 (CSS Variables, Flexbox, Grid), JavaScript (ES6+), Font Awesome 6
- **Visualizations**: Chart.js
- **Diagnostics**: Evidence-based Automatic Learning Gap Detection Algorithm

---

## 🚀 Quick Start Guide

### 1. Prerequisites
- **Java Development Kit (JDK 17 or higher)**
- **Apache Maven 3.8+**
- **MySQL Server (Port 3306)**

### 2. MySQL Database Setup
Ensure MySQL is running on `localhost:3306`.
The application is pre-configured in `src/main/resources/application.properties` with:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/gap_finder?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=Nethra@2008
spring.sql.init.mode=always
spring.sql.init.continue-on-error=true
```
*(If your local MySQL credentials differ, adjust the username and password in `application.properties` accordingly).*

### 3. Build & Run

Open **Windows PowerShell** in the project folder:
```powershell
# Navigate to project
Set-Location "C:\Users\Pavithra\Downloads\GAP-FINDER-FULL\GAP-FINDER-FULL"

# Package into executable JAR
mvn clean package -DskipTests

# Run the application
java -jar target/gap-finder-1.0.0.jar
```
*Alternatively, run via Maven:*
```powershell
mvn spring-boot:run
```

### 4. Access the Platform
Open your browser and navigate to:
```
http://localhost:8080
```

---

## 👥 Demo Accounts (Pre-Seeded)

| Role | Email | Password | Name / Details |
| :--- | :--- | :--- | :--- |
| **Student** | `student@gapfinder.com` | `Student@123` | Aarav Sharma (Grade 10-A, Link Code: `LINK-AARAV10`) |
| **Parent** | `parent@gapfinder.com` | `Parent@123` | Rajesh Sharma (Linked to Aarav Sharma) |
| **Teacher** | `teacher@gapfinder.com` | `Teacher@123` | Dr. Sunita Rao (Assigned to Grade 10-A) |

*(You can also use the 1-click **Quick Demo Buttons** on the landing page for instant access).*

---

## 📊 Core Features & Architecture

### 1. Three Role-Based Dashboards
- **Student Dashboard**: Overall performance score, tests attended, active learning gaps, mastered concepts, interactive curriculum viewer, live test-taking engine with instant grading, question-by-question explanations, and milestone achievement badges.
- **Parent Dashboard**: Verified child academic progress, subject-wise Chart.js performance charts, identified concept gaps with actionable advice for parents to support at home, and test attempt history.
- **Teacher Dashboard**: Assigned class overview, students needing intervention list, class performance charts, student roster with status badges, and an interactive test creator to publish assessments.

### 2. Automatic Learning Gap Detection
Traces knowledge gaps across a 4-tier hierarchy:
$$\text{Subject} \longrightarrow \text{Chapter} \longrightarrow \text{Topic} \longrightarrow \text{Concept} \longrightarrow \text{Question}$$
- Requires multiple attempts before diagnosing weakness (avoids false flags on single mistakes).
- Automatically marks gaps as `RESOLVED` when students achieve $\ge 80\%$ on revision assessments.
- Awards milestone badges (e.g. *Diagnostic Pioneer*, *Math Explorer*, *Gap Closer*).

### 3. Security & Access Control
- Passwords secured with **BCrypt**.
- Session authentication with server-side role validation.
- HTTP `403 Forbidden` enforcement preventing unauthorized cross-role access.
- Strict isolation ensuring parents only access their linked child's records.

---

## 📁 Project Structure

```
GAP-FINDER-FULL/
├── pom.xml
├── README.md
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── gapfinder/
        │           ├── GapFinderApplication.java   # Spring Boot entry point
        │           ├── AuthController.java          # Authentication & sessions
        │           ├── StudentController.java       # Student portal endpoints
        │           ├── ParentController.java        # Parent portal endpoints
        │           ├── TeacherController.java       # Teacher portal endpoints
        │           ├── LearningController.java      # Legacy & AI concept assistant
        │           ├── GapDetectionService.java     # Learning gap evaluation engine
        │           └── DataInitializer.java         # Seed data & curriculum setup
        └── resources/
            ├── application.properties               # MySQL & server configuration
            ├── schema.sql                           # Database DDL & constraints
            └── static/
                └── index.html                       # Responsive Single-Page Application
```

## Subject-based teacher portal enhancements

The completed update adds:
- Explicit teacher-to-subject assignments (`teacher_subjects`). The sample teacher is assigned to Mathematics and Computer Science from the teacher profile; other teacher registrations are mapped from their selected subject text.
- Subject-filtered teacher student roster and subject performance.
- Teacher-created multiple-choice tests, with manually typed questions and marks.
- Test submission status `PENDING_REVIEW`; the assigned subject teacher approves the result and writes feedback.
- Approved feedback is visible to the student and the linked parent.
- PDF study-note upload (maximum 15 MB) and student download.
- Student subject-teacher list and topic-specific YouTube search suggestions based on active learning gaps.

### Database safety

`src/main/resources/schema.sql` now uses `CREATE TABLE IF NOT EXISTS` and does not drop tables. Keep a backup of your MySQL `gap_finder` database before upgrading. On startup, the new tables are created without intentionally deleting existing records.

### Uploaded study notes

PDF files are stored in the application's `uploads/study-materials` folder. Keep this folder when moving or backing up the project.

### Important notes

- A teacher can create tests and upload notes only for subjects assigned to that teacher and for classes assigned to that teacher.
- Manually created questions are linked to the first concept in the selected chapter (or first concept in the subject if no chapter is selected), because the original project schema requires every question to belong to a concept. Select the correct chapter to keep learning-gap analytics as relevant as possible.
- YouTube suggestions open a YouTube search for the identified weak topic; they are not hardcoded video IDs.
