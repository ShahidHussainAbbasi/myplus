package com.myplus.education.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.GradeDTO;
import com.myplus.education.dto.StudentDTO;
import com.myplus.education.dto.VehicleDTO;
import com.myplus.education.entity.FeeSetting;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.School;
import com.myplus.education.entity.Student;
import com.myplus.education.entity.Vehicle;
import com.myplus.education.repository.DiscountRepository;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.repository.VehicleRepository;
import com.myplus.education.service.FeeService;
import com.myplus.education.service.PartyBridgeService;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EDU-IDOR-2 — an edit, or a link, must never reach across tenants.
 *
 * <p>{@code addGrade} / {@code addStudent} / {@code addVehicle} loaded the edited row with a bare
 * {@code findById(dto.id)} and then stamped the CALLER's organisation onto it. The only check in between was
 * {@code canAccessSchool}, which looks at the branch, never the org — and is {@code true} for an owner and for
 * anyone without location grants. So an edit naming another school's row moved it into the caller's school.
 *
 * <p>Each refusal is set up the way the attack really looks: the foreign row EXISTS (a bare {@code findById}
 * would return it) and {@code canAccessSchool} says yes. Only the tenant-scoped finder knows it is not ours.
 * Each refusal is paired with the legitimate path still saving — a deny-everything guard would pass the first.
 */
@ExtendWith(MockitoExtension.class)
class EducationTenantWriteTest {

    private static final Long ORG = 52L, USER = 106L;
    private static final Long OWN_SCHOOL = 10L, FOREIGN_SCHOOL = 90L;
    private static final Long FOREIGN_ID = 900L, OWN_ID = 100L;

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(USER, "owner.education@myplus.com", List.of(), ORG);
    }

    /** The attack's preconditions: branch check passes for everything; only our own school resolves in-tenant. */
    private static void tenantOf(RequestUtil requestUtil, SchoolRepository schoolRepository) {
        lenient().when(requestUtil.getCurrentUser()).thenReturn(caller());
        lenient().when(requestUtil.canAccessSchool(any())).thenReturn(true);
        lenient().when(schoolRepository.findByIdScoped(anyLong(), eq(ORG), eq(USER))).thenReturn(Optional.empty());
        lenient().when(schoolRepository.findByIdScoped(eq(OWN_SCHOOL), eq(ORG), eq(USER)))
                .thenReturn(Optional.of(new School()));
    }

    // ── Student ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addStudent")
    class StudentWrites {

        @Mock private StudentRepository studentRepository;
        @Mock private SchoolRepository schoolRepository;
        @Mock private GradeRepository gradeRepository;
        @Mock private GuardianRepository guardianRepository;
        @Mock private DiscountRepository discountRepository;
        @Mock private VehicleRepository vehicleRepository;
        @Mock private FeeService feeService;
        @Mock private PartyBridgeService partyBridgeService;
        @Mock private RequestUtil requestUtil;
        @Mock private com.myplus.education.util.ScopedDeleter scopedDeleter;
        @Spy private AppUtil appUtil = new AppUtil();

        @InjectMocks private StudentController controller;

        private Student foreignPupil;

        @BeforeEach
        void setUp() {
            tenantOf(requestUtil, schoolRepository);
            foreignPupil = new Student();
            foreignPupil.setId(FOREIGN_ID);
            foreignPupil.setOrganizationId(24L);
            foreignPupil.setName("Another school's pupil");
            // What the old code read: the row exists — a bare findById hands it over.
            lenient().when(studentRepository.findById(FOREIGN_ID)).thenReturn(Optional.of(foreignPupil));
            lenient().when(studentRepository.save(any(Student.class))).thenAnswer(i -> i.getArgument(0));
            lenient().when(feeService.settingFor(any(), any())).thenReturn(new FeeSetting());
        }

        private StudentDTO dto(String name) {
            StudentDTO d = new StudentDTO();
            d.setName(name);
            d.setSchoolId(OWN_SCHOOL);
            return d;
        }

        @Test
        @DisplayName("editing ANOTHER tenant's student is NOT_FOUND, and that pupil is left untouched")
        void foreignEditRefused() {
            StudentDTO d = dto("HIJACKED");
            d.setId(FOREIGN_ID);

            GenericResponse r = controller.addStudent(d, null);

            assertThat(r.getStatus()).isEqualTo("NOT_FOUND");
            verify(studentRepository, never()).save(any());
            assertThat(foreignPupil.getOrganizationId()).as("the pupil must not move into the caller's org").isEqualTo(24L);
            assertThat(foreignPupil.getName()).isEqualTo("Another school's pupil");
        }

        @Test
        @DisplayName("an unknown id is NOT_FOUND — it no longer silently CREATES a student")
        void unknownIdDoesNotCreate() {
            StudentDTO d = dto("Ghost");
            d.setId(12345L);

            assertThat(controller.addStudent(d, null).getStatus()).isEqualTo("NOT_FOUND");
            verify(studentRepository, never()).save(any());
        }

        @Test
        @DisplayName("filing a student under ANOTHER tenant's school is refused")
        void foreignSchoolRefused() {
            StudentDTO d = dto("New pupil");
            d.setSchoolId(FOREIGN_SCHOOL);

            assertThat(controller.addStudent(d, null).getStatus()).isEqualTo("FAILED");
            verify(studentRepository, never()).save(any());
        }

        @Test
        @DisplayName("linking ANOTHER tenant's class is refused — the fee would be billed from it")
        void foreignGradeRefused() {
            StudentDTO d = dto("New pupil");
            d.setGradeId(FOREIGN_ID);

            GenericResponse r = controller.addStudent(d, null);

            assertThat(r.getStatus()).isEqualTo("FAILED");
            assertThat(r.getMessage()).contains("class");
            verify(studentRepository, never()).save(any());
        }

        @Test
        @DisplayName("linking ANOTHER tenant's guardian, discount or vehicle is refused, each by name")
        void foreignLinksRefused() {
            StudentDTO g = dto("p1");
            g.setGuardianId(FOREIGN_ID);
            assertThat(controller.addStudent(g, null).getMessage()).contains("guardian");

            StudentDTO di = dto("p2");
            di.setDiscountId(FOREIGN_ID);
            assertThat(controller.addStudent(di, null).getMessage()).contains("discount");

            StudentDTO v = dto("p3");
            v.setVehicleId(FOREIGN_ID);
            assertThat(controller.addStudent(v, null).getMessage()).contains("vehicle");

            verify(studentRepository, never()).save(any());
        }

        @Test
        @DisplayName("CONTROL: the caller's own student, linked to the caller's own class, still saves")
        void ownEditStillSaves() {
            Student mine = new Student();
            mine.setId(OWN_ID);
            mine.setOrganizationId(ORG);
            mine.setSchoolId(OWN_SCHOOL);
            when(studentRepository.findByIdScoped(OWN_ID, ORG, USER)).thenReturn(Optional.of(mine));
            when(gradeRepository.findByIdScoped(OWN_ID, ORG, USER)).thenReturn(Optional.of(new Grade()));

            StudentDTO d = dto("Renamed");
            d.setId(OWN_ID);
            d.setGradeId(OWN_ID);

            assertThat(controller.addStudent(d, null).getStatus()).isEqualTo("SUCCESS");
            assertThat(mine.getName()).isEqualTo("Renamed");
            assertThat(mine.getGradeId()).isEqualTo(OWN_ID);
        }

        @Test
        @DisplayName("CONTROL: a new student with every picker left empty still saves")
        void emptyPickersStillSave() {
            assertThat(controller.addStudent(dto("Plain pupil"), null).getStatus()).isEqualTo("SUCCESS");
        }
    }

    // ── Grade ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addGrade")
    class GradeWrites {

        @Mock private GradeRepository gradeRepository;
        @Mock private SchoolRepository schoolRepository;
        @Mock private RequestUtil requestUtil;
        @Mock private com.myplus.education.util.ScopedDeleter scopedDeleter;
        @Spy private AppUtil appUtil = new AppUtil();

        @InjectMocks private GradeController controller;

        private Grade foreignClass;

        @BeforeEach
        void setUp() {
            tenantOf(requestUtil, schoolRepository);
            foreignClass = new Grade();
            foreignClass.setId(FOREIGN_ID);
            foreignClass.setOrganizationId(24L);
            foreignClass.setName("Their class");
            lenient().when(gradeRepository.findById(FOREIGN_ID)).thenReturn(Optional.of(foreignClass));
            lenient().when(gradeRepository.save(any(Grade.class))).thenAnswer(i -> i.getArgument(0));
        }

        private GradeDTO dto(Long id, Long school) {
            GradeDTO d = new GradeDTO();
            d.setId(id);
            d.setName("Class X");
            d.setSchoolId(school);
            return d;
        }

        @Test
        @DisplayName("editing ANOTHER tenant's class is NOT_FOUND, and that class is left untouched")
        void foreignEditRefused() {
            assertThat(controller.addGrade(dto(FOREIGN_ID, OWN_SCHOOL), null).getStatus()).isEqualTo("NOT_FOUND");
            verify(gradeRepository, never()).save(any());
            assertThat(foreignClass.getOrganizationId()).isEqualTo(24L);
        }

        @Test
        @DisplayName("filing a class under ANOTHER tenant's school is refused")
        void foreignSchoolRefused() {
            assertThat(controller.addGrade(dto(null, FOREIGN_SCHOOL), null).getStatus()).isEqualTo("FAILED");
            verify(gradeRepository, never()).save(any());
        }

        @Test
        @DisplayName("CONTROL: the caller's own class still saves")
        void ownEditStillSaves() {
            Grade mine = new Grade();
            mine.setId(OWN_ID);
            mine.setSchoolId(OWN_SCHOOL);
            when(gradeRepository.findByIdScoped(OWN_ID, ORG, USER)).thenReturn(Optional.of(mine));

            assertThat(controller.addGrade(dto(OWN_ID, OWN_SCHOOL), null).getStatus()).isEqualTo("SUCCESS");
            assertThat(mine.getName()).isEqualTo("Class X");
        }
    }

    // ── Vehicle ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addVehicle")
    class VehicleWrites {

        @Mock private VehicleRepository vehicleRepository;
        @Mock private SchoolRepository schoolRepository;
        @Mock private RequestUtil requestUtil;
        @Mock private com.myplus.education.util.ScopedDeleter scopedDeleter;
        @Spy private AppUtil appUtil = new AppUtil();

        @InjectMocks private VehicleController controller;

        private Vehicle foreignBus;

        @BeforeEach
        void setUp() {
            tenantOf(requestUtil, schoolRepository);
            foreignBus = new Vehicle();
            foreignBus.setId(FOREIGN_ID);
            foreignBus.setOrganizationId(24L);
            lenient().when(vehicleRepository.findById(FOREIGN_ID)).thenReturn(Optional.of(foreignBus));
            lenient().when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(i -> i.getArgument(0));
        }

        private VehicleDTO dto(Long id, Long school) {
            VehicleDTO d = new VehicleDTO();
            d.setId(id);
            d.setNumber("LEA-" + (id == null ? "new" : id));
            d.setSchoolId(school);
            return d;
        }

        @Test
        @DisplayName("editing ANOTHER tenant's vehicle is NOT_FOUND, and that vehicle is left untouched")
        void foreignEditRefused() {
            assertThat(controller.addVehicle(dto(FOREIGN_ID, OWN_SCHOOL), null).getStatus()).isEqualTo("NOT_FOUND");
            verify(vehicleRepository, never()).save(any());
            assertThat(foreignBus.getOrganizationId()).isEqualTo(24L);
        }

        @Test
        @DisplayName("filing a vehicle under ANOTHER tenant's school is refused")
        void foreignSchoolRefused() {
            assertThat(controller.addVehicle(dto(null, FOREIGN_SCHOOL), null).getStatus()).isEqualTo("FAILED");
            verify(vehicleRepository, never()).save(any());
        }

        @Test
        @DisplayName("CONTROL: the caller's own vehicle still saves")
        void ownEditStillSaves() {
            Vehicle mine = new Vehicle();
            mine.setId(OWN_ID);
            mine.setSchoolId(OWN_SCHOOL);
            when(vehicleRepository.findByIdScoped(OWN_ID, ORG, USER)).thenReturn(Optional.of(mine));

            assertThat(controller.addVehicle(dto(OWN_ID, OWN_SCHOOL), null).getStatus()).isEqualTo("SUCCESS");
            assertThat(mine.getNumber()).isEqualTo("LEA-" + OWN_ID);
        }
    }
}
