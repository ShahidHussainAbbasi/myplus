package com.myplus.education.service;

import com.myplus.education.entity.Student;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.util.RequestUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Which students the caller may see — the role × branch rule, in ONE place.
 *
 * <p>Extracted during slice 1.5. The identical method existed in {@code StudentController},
 * {@code AttendanceController} and {@code MarkController}, and this slice needed a fourth copy. Three
 * copies of a VISIBILITY rule is a security problem waiting to happen, not just duplication: the day one
 * copy is tightened and the others are not, the gap is invisible — nothing in the code says the copies
 * were ever meant to agree.
 *
 * <p>The rule (P4, role × branch):
 * <ul>
 *   <li>owner/super — every student in the organization</li>
 *   <li>a user with no branch grants — every student in the organization (branch scoping is opt-in;
 *       a single-campus school never configures it)</li>
 *   <li>a branch-constrained user — only students of the schools they are granted</li>
 * </ul>
 */
@Service
public class StudentVisibilityService {

    @Autowired private StudentRepository studentRepository;
    @Autowired private RequestUtil requestUtil;

    @Transactional(readOnly = true)
    public List<Student> visibleStudents(Long orgId, Long userId) {
        if (requestUtil.isOwnerSuper()) return studentRepository.findScoped(orgId, userId);
        Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return studentRepository.findScoped(orgId, userId);
        return studentRepository.findScopedBySchools(orgId, schools);
    }

    /**
     * Whether one enrolment number is visible to the caller.
     *
     * <p>Callers must answer NOT_FOUND rather than FORBIDDEN when this is false: telling an unauthorised
     * caller that a student exists but belongs to another campus is itself a disclosure (1.3 D3 made the
     * same choice when skipping out-of-branch rows silently).
     */
    @Transactional(readOnly = true)
    public boolean isVisible(Long orgId, Long userId, String enrollNo) {
        if (enrollNo == null || enrollNo.isBlank()) return false;
        String wanted = enrollNo.trim();
        return visibleStudents(orgId, userId).stream().anyMatch(s -> wanted.equals(s.getEnrollNo()));
    }
}
