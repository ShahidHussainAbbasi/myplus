package com.myplus.education.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Staff;
import com.myplus.education.entity.Subject;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.StaffRepository;
import com.myplus.education.repository.SubjectRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Owner-configurable BRANCH scoping for staff and subjects (slice: edu-branch-scope-settings).
 *
 * Pure logic — mocked collaborators, no database, no Docker — so it runs on every {@code mvn test}.
 *
 * What it pins down is the shape of the policy, which is easy to get subtly wrong:
 *  - OFF (the default) must change nothing;
 *  - an owner/super, or a caller with NO branch grants, still sees org-wide — otherwise the first
 *    admin to enable it is met with an empty screen and assumes the data is gone;
 *  - a record attached to no class stays visible (design D4) — hiding it would make rows vanish the
 *    moment the toggle flips;
 *  - a teacher covering two campuses is visible from both.
 *
 * Tenant isolation is NOT tested here: it lives in the repository's findScoped and is not configurable.
 */
@ExtendWith(MockitoExtension.class)
class EducationBranchScopeTest {

    private static final Long ORG = 1L, USER = 7L;
    private static final Long SCHOOL_A = 100L, SCHOOL_B = 200L;

    /** Classes: A1/A2 at branch A, B1 at branch B. */
    private static Grade grade(Long id, Long schoolId) {
        Grade g = new Grade();
        g.setId(id);
        g.setSchoolId(schoolId);
        return g;
    }

    private static final Grade A1 = grade(11L, SCHOOL_A);
    private static final Grade A2 = grade(12L, SCHOOL_A);
    private static final Grade B1 = grade(21L, SCHOOL_B);

    /**
     * AuthenticatedUser has no no-arg constructor — declaring the explicit legacy constructor suppresses the
     * Lombok-generated one. Use that legacy (pre multi-location) constructor: it defaults the location fields to
     * unset, which is what these tests want, since branch visibility here is DERIVED from class assignments
     * rather than from a location grant.
     */
    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(USER, "branch-scope-test@myplus.com", List.of(), ORG);
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(GenericResponse r) {
        return ((List<Object>) r.getCollection()).stream()
                .map(d -> {
                    try {
                        return (String) d.getClass().getMethod("getName").invoke(d);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    // ── Staff ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Staff — derived from the classes they are assigned to")
    class StaffScope {

        @Mock private StaffRepository staffRepository;
        @Mock private GradeRepository gradeRepository;
        @Mock private RequestUtil requestUtil;
        @Mock private SettingsService settingsService;
        @Mock private AppUtil appUtil;
        @Mock private com.myplus.education.util.ScopedDeleter scopedDeleter;

        @InjectMocks private StaffController controller;

        /** Ids matter here: the PICKER renders {@code <option value=ID>} and SKIPS id-less rows, so a
         *  fixture without one can never appear in it — the list path maps to DTOs and does not care. */
        private long nextStaffId = 1;

        private Staff staff(String name, Grade... assigned) {
            Staff s = new Staff();
            s.setId(nextStaffId++);
            s.setName(name);
            s.setGrades(assigned.length == 0 ? List.of() : List.of(assigned));
            return s;
        }

        @BeforeEach
        void setUp() {
            lenient().when(requestUtil.getCurrentUser()).thenReturn(caller());
            lenient().when(staffRepository.findScoped(any(), any())).thenReturn(List.of(
                    staff("BranchA Teacher", A1),
                    staff("BranchB Teacher", B1),
                    staff("Roaming Teacher", A2, B1),   // covers both campuses
                    staff("Unassigned Teacher")          // no class at all
            ));
            lenient().when(gradeRepository.findScopedBySchools(eq(ORG), anyCollection()))
                    .thenReturn(List.of(A1, A2));        // the caller's branch = A
        }

        @Test
        void off_by_default_shows_every_branch() {
            when(settingsService.getBool("edu.staff.branchScoped")).thenReturn(false);

            assertThat(names(controller.getUserStaff(null)))
                    .as("the default must not change what anyone sees")
                    .containsExactly("BranchA Teacher", "BranchB Teacher", "Roaming Teacher", "Unassigned Teacher");
        }

        @Test
        void on_narrows_to_the_callers_branch() {
            when(settingsService.getBool("edu.staff.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(false);
            when(requestUtil.accessibleSchoolIds()).thenReturn(Set.of(SCHOOL_A));

            List<String> visible = names(controller.getUserStaff(null));

            assertThat(visible).contains("BranchA Teacher");
            assertThat(visible).as("another campus's teacher is hidden").doesNotContain("BranchB Teacher");
        }

        @Test
        void a_teacher_covering_two_campuses_is_visible_from_both() {
            when(settingsService.getBool("edu.staff.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(false);
            when(requestUtil.accessibleSchoolIds()).thenReturn(Set.of(SCHOOL_A));

            assertThat(names(controller.getUserStaff(null)))
                    .as("deriving from classes — not a single school_id — is what makes this work")
                    .contains("Roaming Teacher");
        }

        @Test
        void staff_assigned_to_no_class_stay_visible() {
            when(settingsService.getBool("edu.staff.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(false);
            when(requestUtil.accessibleSchoolIds()).thenReturn(Set.of(SCHOOL_A));

            assertThat(names(controller.getUserStaff(null)))
                    .as("design D4 — a record with no derivable branch must not vanish when the toggle flips")
                    .contains("Unassigned Teacher");
        }

        @Test
        void an_owner_still_sees_every_branch() {
            when(settingsService.getBool("edu.staff.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(true);

            assertThat(names(controller.getUserStaff(null)))
                    .as("the owner runs the group — the policy is for branch staff")
                    .contains("BranchA Teacher", "BranchB Teacher");
        }

        @Test
        void a_caller_with_no_branch_grants_sees_every_branch() {
            when(settingsService.getBool("edu.staff.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(false);
            when(requestUtil.accessibleSchoolIds()).thenReturn(Set.of());

            assertThat(names(controller.getUserStaff(null)))
                    .as("never show an empty screen to an admin who simply has no grants yet")
                    .contains("BranchA Teacher", "BranchB Teacher");
        }

        @Test
        void the_picker_is_scoped_too() {
            when(settingsService.getBool("edu.staff.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(false);
            when(requestUtil.accessibleSchoolIds()).thenReturn(Set.of(SCHOOL_A));

            String html = controller.getUserStaffs(null);

            assertThat(html).contains("BranchA Teacher");
            assertThat(html)
                    .as("a dropdown offering what the list hides is a way around the policy")
                    .doesNotContain("BranchB Teacher");
        }
    }

    // ── Subjects ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Subjects — derived from the class they are attached to")
    class SubjectScope {

        @Mock private SubjectRepository subjectRepository;
        @Mock private GradeRepository gradeRepository;
        @Mock private RequestUtil requestUtil;
        @Mock private SettingsService settingsService;
        @Mock private AppUtil appUtil;
        @Mock private com.myplus.education.util.ScopedDeleter scopedDeleter;

        @InjectMocks private SubjectController controller;

        /** See the staff fixture: the picker skips id-less rows, so subjects need ids to be assertable. */
        private long nextSubjectId = 1;

        private Subject subject(String name, Grade attached) {
            Subject s = new Subject();
            s.setId(nextSubjectId++);
            s.setName(name);
            s.setGrade(attached);
            return s;
        }

        @BeforeEach
        void setUp() {
            lenient().when(requestUtil.getCurrentUser()).thenReturn(caller());
            lenient().when(subjectRepository.findScoped(any(), any())).thenReturn(List.of(
                    subject("BranchA Maths", A1),
                    subject("BranchB Maths", B1),
                    subject("Shared Ethics", null)      // attached to no class
            ));
            lenient().when(gradeRepository.findScopedBySchools(eq(ORG), anyCollection()))
                    .thenReturn(List.of(A1, A2));
        }

        @Test
        void off_by_default_shows_the_whole_curriculum() {
            when(settingsService.getBool("edu.subject.branchScoped")).thenReturn(false);

            assertThat(names(controller.getUserSubject(null)))
                    .containsExactly("BranchA Maths", "BranchB Maths", "Shared Ethics");
        }

        @Test
        void on_narrows_to_the_callers_branch_but_keeps_unattached_subjects() {
            when(settingsService.getBool("edu.subject.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(false);
            when(requestUtil.accessibleSchoolIds()).thenReturn(Set.of(SCHOOL_A));

            List<String> visible = names(controller.getUserSubject(null));

            assertThat(visible).contains("BranchA Maths");
            assertThat(visible).doesNotContain("BranchB Maths");
            assertThat(visible).as("design D4").contains("Shared Ethics");
        }

        @Test
        void an_owner_still_sees_the_whole_curriculum() {
            when(settingsService.getBool("edu.subject.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(true);

            assertThat(names(controller.getUserSubject(null)))
                    .contains("BranchA Maths", "BranchB Maths");
        }

        @Test
        void the_picker_is_scoped_too() {
            // Coverage gap found alongside the staff fixture fix: SubjectController.getUserSubjects DOES
            // scope (it calls branchVisible), but nothing asserted it — while the staff twin was asserted.
            // The design's point stands for both: a dropdown offering what the list hides is a way around
            // the policy.
            when(settingsService.getBool("edu.subject.branchScoped")).thenReturn(true);
            when(requestUtil.isOwnerSuper()).thenReturn(false);
            when(requestUtil.accessibleSchoolIds()).thenReturn(Set.of(SCHOOL_A));

            String html = controller.getUserSubjects(null);

            assertThat(html).contains("BranchA Maths");
            assertThat(html)
                    .as("a dropdown offering what the list hides is a way around the policy")
                    .doesNotContain("BranchB Maths");
            assertThat(html)
                    .as("design D4 — an unattached subject stays visible in the picker too")
                    .contains("Shared Ethics");
        }
    }
}
