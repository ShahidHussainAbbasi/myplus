# EDU-PERM-1 slice 3 — the endpoint → permission map

**2026-09-25.** Built before writing a single guard, because the mapping is where the decisions are; adding
the annotations afterwards is mechanical. Generated from the controllers and cross-referenced against the
five built-in sets as they exist in `myplusdb_auth`.

| | |
|---|---|
| Endpoints mappable to a code | **157** |
| Portal endpoints — **ownership is the guard**, no code | **15** |
| Unmapped | **1** |

## What building the map found, before it could hurt anybody

**1 · Twenty-four proposed codes did not exist.** A first mapper turned `addSubject` into `subject.create`
and `deletePeriod` into `timetable.delete`. V16 deliberately collapsed those into one `edit` per area, and
its labels say so — *"Set up subjects"*, *"Build the timetable"*, *"Set up classes and the academic year"*
all mean add, change **and** remove. A guard naming a code nothing holds locks out everyone but the owner,
so the mapper is now constrained to the catalogue and falls back to the most specific code that really
exists.

**2 · Four codes no staff set held**, which would have stopped the school working the day they were
enforced: `timetable.edit`, `timetable.substitute`, `attendance.staff` and `exam.edit`. In any mainstream
school system those are **Front Office** work — the office timetables, arranges cover when a teacher calls in
sick, and keeps the leave register. Fixed in `V19`; Front Office went 19 → 26 codes.

**3 · `/getConfig` must NOT be gated.** It resolves to `settings.view`, which only Principal holds — but the
endpoint is read at **page load** by every education screen (P7.3 reads the same catalogue to apply the
keyboard policies). Gating it would silently break keyboard navigation for every teacher, accountant and
office user. It is infrastructure, not a privileged read, and is excluded from the map.

## Who loses what, after V19

Enforcement narrows people on purpose — that is the slice. These are endpoints each role can reach today and
will not afterwards:

| Set | Currently-ungated endpoints it loses |
|---|---|
| Teacher | 45 — fee ledgers, staff lists, vehicles, discounts |
| Class Teacher | 33 |
| Accountant | 56 — marks, attendance, homework |
| Front Office | 20 |
| Principal | 0 |

Codes still held by no staff set, all deliberate: `staff.create`, `staff.delete`, `school.edit`,
`settings.edit`, `student.delete`, `reportcard.publish` — the head's own acts. `reportcard.publish` being
restricted is the separation this whole slice exists to express.

## Suggested tranches

1. **The 36 endpoints guarded by `ADMIN_PRIVILEGE` today.** Converting these changes **nobody's** access:
   admins are on Principal, which holds every code, and nobody else could reach them before. A third of the
   work, zero regression risk, and it makes the codes real.
2. **The 9 ungated writes** — including the two `Alerts`/`AlertChannel` controllers, which need the G1
   tenant-scoping fix in the same pass.
3. **`DELETE_PRIVILEGE` and `WRITE_PRIVILEGE` conversions** (37 endpoints).
4. **The 89 reads.** The largest behaviour change, and the one that needs the owner / admin / teacher /
   accountant ladder walked before it ships.

## The map

| Controller | Endpoint | Verb | Guard today | Code |
|---|---|---|---|---|
| AcademicYear | `/addAcademicYear` | POST | ADMIN_PRIVILEGE | `class.edit` |
| AcademicYear | `/addTerm` | POST | ADMIN_PRIVILEGE | `class.edit` |
| AcademicYear | `/deleteAcademicYear` | POST | DELETE_PRIVILEGE | `class.edit` |
| AcademicYear | `/deleteTerm` | POST | DELETE_PRIVILEGE | `class.edit` |
| AcademicYear | `/getAcademicYears` | GET | — | `class.view` |
| AcademicYear | `/getCurrentTerm` | GET | — | `class.view` |
| AcademicYear | `/pinCurrentTerm` | POST | ADMIN_PRIVILEGE | `class.edit` |
| AlertChannel | `/` | GET | — | `communication.view` |
| AlertChannel | `/` | POST | — | `communication.publish` |
| AlertChannel | `/{id}` | GET | — | `communication.view` |
| AlertChannel | `/{id}` | PUT | — | `communication.publish` |
| AlertChannel | `/{id}` | DELETE | DELETE_PRIVILEGE | `communication.publish` |
| Alert | `/addAlerts` | POST | WRITE_PRIVILEGE | `communication.publish` |
| Alert | `/deleteAlerts` | POST | DELETE_PRIVILEGE | `communication.publish` |
| Alert | `/getUserPA` | GET | — | `communication.view` |
| Alert | `/importCSV` | POST | ADMIN_PRIVILEGE | `student.create` |
| Alert | `/sendAlerts` | POST | — | `communication.publish` |
| Alert | `/sendPA` | POST | — | `communication.publish` |
| Alerts | `/` | GET | — | `communication.view` |
| Alerts | `/` | POST | — | `communication.publish` |
| Alerts | `/{id}` | GET | — | `communication.view` |
| Alerts | `/{id}` | PUT | — | `communication.publish` |
| Alerts | `/{id}` | DELETE | DELETE_PRIVILEGE | `communication.publish` |
| Analytics | `/getDashboardAnalytics` | GET | — | `**unmapped**` |
| Attendance | `/deleteA` | POST | DELETE_PRIVILEGE | `attendance.view` |
| Attendance | `/getAllA` | GET | — | `attendance.view` |
| Attendance | `/getClassRoster` | GET | — | `attendance.view` |
| Attendance | `/getUserA` | GET | — | `attendance.view` |
| Attendance | `/getUserStudentMap` | GET | — | `attendance.view` |
| Attendance | `/markAttendanceBulk` | POST | WRITE_PRIVILEGE | `attendance.mark` |
| Behaviour | `/getBehaviourNotes` | GET | — | `behaviour.view` |
| Behaviour | `/saveBehaviourNote` | POST | WRITE_PRIVILEGE | `behaviour.view` |
| Behaviour | `/supersedeBehaviourNote` | POST | WRITE_PRIVILEGE | `behaviour.view` |
| Dashboard | `/getDashboardData` | GET | — | `report.view` |
| Discount | `/addDiscount` | POST | ADMIN_PRIVILEGE | `fee.structure` |
| Discount | `/deleteDiscount` | POST | DELETE_PRIVILEGE | `fee.structure` |
| Discount | `/getAllDiscount` | GET | — | `fee.structure` |
| Discount | `/getUserDiscount` | GET | — | `fee.structure` |
| Discount | `/getUserDiscounts` | GET | — | `fee.structure` |
| Exam | `/addExam` | POST | ADMIN_PRIVILEGE | `exam.edit` |
| Exam | `/addExamPaper` | POST | ADMIN_PRIVILEGE | `exam.edit` |
| Exam | `/deleteExam` | POST | DELETE_PRIVILEGE | `exam.edit` |
| Exam | `/deleteExamPaper` | POST | DELETE_PRIVILEGE | `exam.edit` |
| Exam | `/getDatesheet` | GET | — | `exam.view` |
| Exam | `/getExams` | GET | — | `exam.view` |
| Exam | `/setExamStatus` | POST | ADMIN_PRIVILEGE | `exam.edit` |
| FeeCollection | `/addFc` | POST | ADMIN_PRIVILEGE | `fee.view` |
| FeeCollection | `/deleteFc` | POST | DELETE_PRIVILEGE | `fee.view` |
| FeeCollection | `/getAllFc` | GET | — | `fee.view` |
| FeeCollection | `/getFeeAging` | GET | — | `fee.view` |
| FeeCollection | `/getFeeStatement` | GET | — | `fee.view` |
| FeeCollection | `/getUserFc` | GET | — | `fee.view` |
| Fee | `/getFeeSetting` | GET | — | `fee.structure` |
| Fee | `/loadFL` | GET | — | `fee.view` |
| Fee | `/loadFR` | POST | — | `fee.view` |
| Fee | `/loadFV` | GET | — | `fee.view` |
| Fee | `/saveFeeSetting` | POST | ADMIN_PRIVILEGE | `fee.structure` |
| Grade | `/addGrade` | POST | ADMIN_PRIVILEGE | `class.edit` |
| Grade | `/deleteGrade` | POST | DELETE_PRIVILEGE | `class.edit` |
| Grade | `/getAllGrade` | GET | — | `class.view` |
| Grade | `/getUserGrade` | GET | — | `class.view` |
| Grade | `/getUserGrades` | GET | — | `class.view` |
| Grading | `/applyGradingPreset` | POST | ADMIN_PRIVILEGE | `exam.edit` |
| Grading | `/deleteGradeBand` | POST | DELETE_PRIVILEGE | `exam.edit` |
| Grading | `/getGradingScale` | GET | — | `exam.view` |
| Grading | `/saveGradeBand` | POST | ADMIN_PRIVILEGE | `exam.edit` |
| Guardian | `/addGuardian` | POST | WRITE_PRIVILEGE | `guardian.create` |
| Guardian | `/deleteGuardian` | POST | DELETE_PRIVILEGE | `guardian.edit` |
| Guardian | `/getAllGuardian` | GET | — | `guardian.view` |
| Guardian | `/getUserGuardian` | GET | — | `guardian.view` |
| Guardian | `/getUserGuardians` | GET | — | `guardian.view` |
| GuardianPortal | `/portal/attendance` | GET | — | `*ownership*` |
| GuardianPortal | `/portal/children` | GET | — | `*ownership*` |
| GuardianPortal | `/portal/dues` | GET | — | `*ownership*` |
| GuardianPortal | `/portal/homework` | GET | — | `*ownership*` |
| GuardianPortal | `/portal/me` | GET | — | `*ownership*` |
| GuardianPortal | `/portal/meetings` | GET | — | `*ownership*` |
| GuardianPortal | `/portal/meetings/book` | POST | — | `*ownership*` |
| GuardianPortal | `/portal/notices` | GET | — | `*ownership*` |
| GuardianPortal | `/portal/results` | GET | — | `*ownership*` |
| Homework | `/deleteHomework` | POST | DELETE_PRIVILEGE | `homework.view` |
| Homework | `/getHomework` | GET | — | `homework.view` |
| Homework | `/getHomeworkSheet` | GET | — | `homework.view` |
| Homework | `/saveHomework` | POST | WRITE_PRIVILEGE | `homework.view` |
| Homework | `/saveSubmissionBulk` | POST | WRITE_PRIVILEGE | `homework.view` |
| Leave | `/decideLeaveRequest` | POST | ADMIN_PRIVILEGE | `attendance.staff` |
| Leave | `/deleteLeaveType` | POST | DELETE_PRIVILEGE | `attendance.staff` |
| Leave | `/getLeaveBalances` | GET | — | `attendance.view` |
| Leave | `/getLeaveRequests` | GET | — | `attendance.staff` |
| Leave | `/getLeaveTypes` | GET | — | `attendance.staff` |
| Leave | `/saveLeaveRequest` | POST | WRITE_PRIVILEGE | `attendance.staff` |
| Leave | `/saveLeaveType` | POST | ADMIN_PRIVILEGE | `attendance.staff` |
| Mark | `/getMarksSheet` | GET | — | `marks.enter` |
| Mark | `/getStudentMarks` | GET | — | `marks.enter` |
| Mark | `/saveMarksBulk` | POST | WRITE_PRIVILEGE | `marks.enter` |
| Meeting | `/getMeetingEvents` | GET | — | `communication.view` |
| Meeting | `/getMeetingSlots` | GET | — | `communication.view` |
| Meeting | `/publishMeetingSlots` | POST | ADMIN_PRIVILEGE | `communication.publish` |
| Meeting | `/saveMeetingEvent` | POST | WRITE_PRIVILEGE | `communication.publish` |
| Meeting | `/setMeetingEventStatus` | POST | ADMIN_PRIVILEGE | `communication.publish` |
| Notice | `/deleteNotice` | POST | ADMIN_PRIVILEGE | `communication.publish` |
| Notice | `/getNoticeReach` | GET | — | `communication.view` |
| Notice | `/getNotices` | GET | — | `communication.view` |
| Notice | `/publishNotice` | POST | ADMIN_PRIVILEGE | `communication.publish` |
| Notice | `/saveNotice` | POST | WRITE_PRIVILEGE | `communication.publish` |
| Owner | `/addOwner` | POST | ADMIN_PRIVILEGE | `staff.create` |
| Owner | `/deleteOwner` | POST | DELETE_PRIVILEGE | `staff.delete` |
| Owner | `/getAllOwner` | GET | — | `staff.view` |
| Owner | `/getUserOwner` | GET | — | `staff.view` |
| Owner | `/getUserOwners` | GET | — | `staff.view` |
| PartyLink | `/backfill` | POST | ROLE_OWNER / ADMIN_PRIVILEGE / SUPER_PRIVILEGE | `student.edit` |
| PortalAccess | `/getPortalAccess` | GET | — | `guardian.view` |
| PortalAccess | `/invitePortalAccess` | POST | ADMIN_PRIVILEGE | `guardian.portal` |
| PortalAccess | `/revokePortalAccess` | POST | ADMIN_PRIVILEGE | `guardian.portal` |
| Promotion | `/getPromotionHistory` | GET | — | `student.view` |
| Promotion | `/getPromotionPlan` | GET | — | `student.view` |
| Promotion | `/runPromotion` | POST | ADMIN_PRIVILEGE | `student.promote` |
| Promotion | `/undoPromotion` | POST | ADMIN_PRIVILEGE | `student.promote` |
| ReportCard | `/getReportCard` | GET | — | `reportcard.generate` |
| ReportCard | `/getReportCardPreview` | GET | — | `reportcard.generate` |
| ReportCard | `/getTranscript` | GET | — | `reportcard.view` |
| ReportCard | `/publishReportCard` | POST | ADMIN_PRIVILEGE | `reportcard.publish` |
| ReportCard | `/withdrawReportCard` | POST | ADMIN_PRIVILEGE | `reportcard.publish` |
| School | `/addSchool` | POST | ADMIN_PRIVILEGE | `school.edit` |
| School | `/deleteSchool` | POST | DELETE_PRIVILEGE | `school.edit` |
| School | `/getAllSchool` | GET | — | `school.view` |
| School | `/getMainBranchName` | GET | — | `school.view` |
| School | `/getMySchools` | GET | — | `school.view` |
| School | `/getUserSchool` | GET | — | `school.view` |
| School | `/getUserSchools` | GET | — | `school.view` |
| Settings | `/getConfig` | GET | — | `settings.view` |
| Settings | `/saveConfig` | POST | ROLE_OWNER / ADMIN_PRIVILEGE | `settings.edit` |
| StaffAttendance | `/getStaffRegister` | GET | — | `attendance.view` |
| StaffAttendance | `/markStaffAttendanceBulk` | POST | ADMIN_PRIVILEGE | `attendance.mark` |
| Staff | `/addStaff` | POST | WRITE_PRIVILEGE | `staff.create` |
| Staff | `/deleteStaff` | POST | DELETE_PRIVILEGE | `staff.delete` |
| Staff | `/getAllStaff` | GET | — | `staff.view` |
| Staff | `/getUserStaff` | GET | — | `staff.view` |
| Staff | `/getUserStaffs` | GET | — | `staff.view` |
| Student | `/addStudent` | POST | WRITE_PRIVILEGE | `student.create` |
| Student | `/deleteStudent` | POST | DELETE_PRIVILEGE | `student.delete` |
| Student | `/getAllStudent` | GET | — | `student.view` |
| Student | `/getUserStudent` | GET | — | `student.view` |
| Student | `/getUserStudents` | GET | — | `student.view` |
| Student | `/impStudents` | POST | — | `student.create` |
| StudentPortal | `/portal/my/attendance` | GET | — | `*ownership*` |
| StudentPortal | `/portal/my/homework` | GET | — | `*ownership*` |
| StudentPortal | `/portal/my/me` | GET | — | `*ownership*` |
| StudentPortal | `/portal/my/notices` | GET | — | `*ownership*` |
| StudentPortal | `/portal/my/results` | GET | — | `*ownership*` |
| StudentPortal | `/portal/my/timetable` | GET | — | `*ownership*` |
| Subject | `/addSubject` | POST | WRITE_PRIVILEGE | `subject.edit` |
| Subject | `/deleteSubject` | POST | DELETE_PRIVILEGE | `subject.edit` |
| Subject | `/getAllSubject` | GET | — | `subject.view` |
| Subject | `/getUserSubject` | GET | — | `subject.view` |
| Subject | `/getUserSubjects` | GET | — | `subject.view` |
| Substitution | `/assignSubstitute` | POST | ADMIN_PRIVILEGE | `timetable.substitute` |
| Substitution | `/clearStaffAbsence` | POST | ADMIN_PRIVILEGE | `timetable.substitute` |
| Substitution | `/clearSubstitute` | POST | ADMIN_PRIVILEGE | `timetable.substitute` |
| Substitution | `/getSubstitutionDay` | GET | — | `timetable.view` |
| Substitution | `/markStaffAbsent` | POST | ADMIN_PRIVILEGE | `timetable.substitute` |
| Timetable | `/copyTimetable` | POST | ADMIN_PRIVILEGE | `timetable.edit` |
| Timetable | `/deletePeriod` | POST | DELETE_PRIVILEGE | `timetable.edit` |
| Timetable | `/deleteTimetableEntry` | POST | DELETE_PRIVILEGE | `timetable.edit` |
| Timetable | `/getPeriods` | GET | — | `timetable.view` |
| Timetable | `/getTimetable` | GET | — | `timetable.view` |
| Timetable | `/savePeriod` | POST | ADMIN_PRIVILEGE | `timetable.edit` |
| Timetable | `/saveTimetableEntry` | POST | ADMIN_PRIVILEGE | `timetable.edit` |
| Vehicle | `/addVehicle` | POST | ADMIN_PRIVILEGE | `transport.edit` |
| Vehicle | `/deleteVehicle` | POST | DELETE_PRIVILEGE | `transport.edit` |
| Vehicle | `/getAllVehicle` | GET | — | `transport.view` |
| Vehicle | `/getUserVehicle` | GET | — | `transport.view` |
| Vehicle | `/getUserVehicles` | GET | — | `transport.view` |
