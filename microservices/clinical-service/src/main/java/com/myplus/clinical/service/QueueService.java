package com.myplus.clinical.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.myplus.clinical.config.AppointmentDirectoryClient;
import com.myplus.clinical.config.AppointmentDirectoryClient.Doctor;
import com.myplus.clinical.dto.QueueDtos.DayRequest;
import com.myplus.clinical.dto.QueueDtos.DoctorView;
import com.myplus.clinical.dto.QueueDtos.IssueRequest;
import com.myplus.clinical.dto.QueueDtos.NewDoctorRequest;
import com.myplus.clinical.dto.QueueDtos.TokenView;
import com.myplus.clinical.entity.ClinicProvider;
import com.myplus.clinical.entity.Patient;
import com.myplus.clinical.entity.ProviderDay;
import com.myplus.clinical.entity.QueueStatus;
import com.myplus.clinical.entity.QueueToken;
import com.myplus.clinical.repository.ClinicProviderRepo;
import com.myplus.clinical.repository.PatientRepo;
import com.myplus.clinical.repository.ProviderDayRepo;
import com.myplus.clinical.repository.QueueTokenRepo;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * HMS S2 — today's line (design §4b): the doctors with today's numbers, issuing a token, the board, and every
 * status move.
 *
 * <h3>What the database decides, and what this class decides</h3>
 * One live token per patient per doctor per day is uq_token_live; a status move is one conditional UPDATE. This
 * class adds the rules a single row cannot see: the doctor's daily limit and closed days, the "several doctors a
 * day" setting, and "with Dr X now". Those three are checked before the write, so two desks acting in the same
 * instant could pass them together — at worst one token over a day's limit; never two doctors on one token, which
 * the UPDATE prevents. Stated here rather than discovered.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueTokenRepo tokens;
    private final PatientRepo patients;
    private final ClinicProviderRepo prefixes;
    private final ProviderDayRepo days;
    private final TokenWriter writer;
    private final AppointmentDirectoryClient directory;
    private final ClinicAccess access;
    private final ClinicSettings settings;
    private final ClinicAuditService audit;

    // ── doctors ─────────────────────────────────────────────────────────────────────────────────────────

    /** The clinic's doctors with today's numbers: one call to appointment-service, one board read. */
    public List<DoctorView> doctors() {
        access.assertModuleOn();
        Long org = access.org();
        LocalDate today = TenantClock.today();
        List<Doctor> ds = directoryDoctors();
        Map<Long, String> letters = prefixes.findByOrganizationId(org).stream()
                .collect(Collectors.toMap(ClinicProvider::getProviderId, ClinicProvider::getTokenPrefix));
        Map<Long, ProviderDay> changes = days.findByOrganizationIdAndVisitDate(org, today).stream()
                .collect(Collectors.toMap(ProviderDay::getProviderId, Function.identity()));
        Map<Long, List<QueueToken>> byDoctor = tokens.board(org, today).stream()
                .collect(Collectors.groupingBy(QueueToken::getProviderId));
        return ds.stream().map(d -> {
            Integer usual = QueueRules.usualLimit(d);
            ProviderDay day = changes.get(d.getId());
            List<QueueToken> mine = byDoctor.getOrDefault(d.getId(), List.of());
            return DoctorView.builder()
                    .id(d.getId()).name(d.getName()).speciality(d.getSpeciality()).fee(d.getFee())
                    .tokenPrefix(letters.get(d.getId()))
                    .usualLimit(usual).todayLimit(QueueRules.todayLimit(usual, day))
                    .todayChanged(day != null).closedToday(day != null && Boolean.TRUE.equals(day.getClosed()))
                    .issuedToday(mine.stream().filter(t -> !QueueStatus.CANCELLED.equals(t.getStatus())).count())
                    .waitingNow(mine.stream().filter(t -> QueueStatus.WAITING.equals(t.getStatus())).count())
                    .withDoctorNow(mine.stream().filter(t -> QueueStatus.WITH_DOCTOR.contains(t.getStatus())).count())
                    .build();
        }).sorted(Comparator.comparing(DoctorView::getName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Add a doctor to the clinic: appointment-service owns the doctor; the clinic's first venue is made if needed. */
    public DoctorView addDoctor(NewDoctorRequest req) {
        access.assertModuleOn();
        Long org = access.org();
        String name = req.getName() == null ? "" : req.getName().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new ValidationException("Enter the doctor's name.");
        if (req.getDailyLimit() != null && req.getDailyLimit() < 0) throw new ValidationException("The daily limit cannot be negative.");
        Long venueId = clinicVenue();
        Integer limit = req.getDailyLimit() == null || req.getDailyLimit() == 0 ? null : req.getDailyLimit();
        AppointmentDirectoryClient.Envelope<Doctor> made;
        try {
            made = directory.createDoctor(Doctor.builder()
                .hospitalId(venueId).name(name).speciality(trim(req.getSpeciality())).fee(trim(req.getFee()))
                .mobile(trim(req.getMobile())).appointmentOfferType("count").appointmentOfferValue(limit).build());
        } catch (org.springframework.web.client.RestClientException down) {
            // a WRITE: never retried blind (it may have landed) — said in words, and the list shows whether it did
            throw new ValidationException("The doctor could not be saved right now. Check the Doctors list, then try again.");
        }
        if (made == null || !made.isSuccess() || made.getData() == null) {
            throw new ValidationException(made != null && made.getMessage() != null ? made.getMessage() : "The doctor could not be saved.");
        }
        Doctor d = made.getData();
        prefixFor(org, d.getId());
        audit.record(AuditRecord.builder().action("CLINIC_DOCTOR_ADD").entityType("PROVIDER")
                .entityRef(String.valueOf(d.getId())).details(limit == null ? "no daily limit" : "limit " + limit).build());
        return doctors().stream().filter(v -> v.getId().equals(d.getId())).findFirst().orElseThrow();
    }

    /** One day's change: another limit, closed, or back to the usual. */
    public DoctorView setDay(DayRequest req) {
        access.assertModuleOn();
        Long org = access.org();
        Doctor d = doctorOf(req.getProviderId());
        LocalDate day = req.getDate() == null || req.getDate().isBlank() ? TenantClock.today() : LocalDate.parse(req.getDate().trim());
        if (day.isBefore(TenantClock.today())) throw new ValidationException("A day that has passed cannot be changed.");
        ProviderDay.Key key = new ProviderDay.Key(org, d.getId(), day);
        if (req.isReset()) {
            days.findById(key).ifPresent(days::delete);
        } else {
            if (req.getLimit() != null && req.getLimit() < 0) throw new ValidationException("The limit cannot be negative.");
            ProviderDay pd = days.findById(key).orElseGet(() -> {
                ProviderDay n = new ProviderDay();
                n.setOrganizationId(org); n.setProviderId(d.getId()); n.setVisitDate(day);
                return n;
            });
            pd.setCap(req.getLimit() == null ? 0 : req.getLimit());   // 0 = no limit that day
            pd.setClosed(req.isClosed());
            pd.setUpdatedBy(access.userId());
            pd.setUpdatedAt(LocalDateTime.now());
            days.save(pd);
        }
        audit.record(AuditRecord.builder().action("CLINIC_DOCTOR_DAY").entityType("PROVIDER").entityRef(String.valueOf(d.getId()))
                .details(day + (req.isReset() ? " usual limit" : req.isClosed() ? " closed" : " limit " + req.getLimit())).build());
        return doctors().stream().filter(v -> v.getId().equals(d.getId())).findFirst().orElseThrow();
    }

    // ── tokens ──────────────────────────────────────────────────────────────────────────────────────────

    public TokenView issue(IssueRequest req) {
        access.assertModuleOn();
        Long org = access.org();
        LocalDate today = TenantClock.today();
        if (req.getPatientId() == null) throw new ValidationException("Choose the patient first.");
        if (req.getProviderId() == null) throw new ValidationException("Choose the doctor.");
        Patient p = patients.findByIdAndOrganizationId(req.getPatientId(), org)
                .filter(x -> Patient.ACTIVE.equals(x.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found."));
        Doctor d = doctorOf(req.getProviderId());

        // Same doctor, same day, still live: the existing token is the answer (02b). The index decides; this words it.
        List<QueueToken> live = tokens.patientTokens(org, p.getId(), today, QueueStatus.LIVE);
        live.stream().filter(t -> t.getProviderId().equals(d.getId())).findFirst().ifPresent(t -> {
            throw new ValidationException(p.getName() + " already has token " + t.getTokenLabel() + " with " + d.getName() + " today.");
        });
        if (!settings.multiDoctorPerDay() && !live.isEmpty()) {
            QueueToken t = live.get(0);
            throw new ValidationException(p.getName() + " already has token " + t.getTokenLabel() + " with "
                    + t.getProviderName() + " today. This clinic gives one token per patient per day.");
        }

        ProviderDay day = days.findById(new ProviderDay.Key(org, d.getId(), today)).orElse(null);
        if (day != null && Boolean.TRUE.equals(day.getClosed())) {
            throw new ValidationException(d.getName() + " is not seeing patients today.");
        }
        Integer limit = QueueRules.todayLimit(QueueRules.usualLimit(d), day);
        if (limit != null && tokens.countIssued(org, d.getId(), today) >= limit) {
            throw new ValidationException(d.getName() + "'s limit for today (" + limit + " patients) is reached.");
        }

        String prefix = prefixFor(org, d.getId());
        QueueToken t = new QueueToken();
        t.setOrganizationId(org);
        t.setPatientId(p.getId());
        t.setProviderId(d.getId());
        t.setProviderName(d.getName());
        t.setVisitDate(today);
        t.setStatus(QueueStatus.WAITING);
        t.setCreatedBy(access.userId());
        QueueToken saved;
        try {
            saved = writer.insert(t, prefix);
        } catch (DataIntegrityViolationException raced) {
            // another desk gave this patient a token with this doctor a moment ago
            QueueToken other = tokens.patientTokens(org, p.getId(), today, QueueStatus.LIVE).stream()
                    .filter(x -> x.getProviderId().equals(d.getId())).findFirst().orElseThrow(() -> raced);
            throw new ValidationException(p.getName() + " already has token " + other.getTokenLabel() + " with " + d.getName() + " today.");
        }
        audit.record(AuditRecord.builder().action("TOKEN_ISSUE").entityType("TOKEN").entityRef(saved.getTokenLabel())
                .details(p.getMrn() + " with doctor " + d.getId()).build());
        return view(saved, p, tokens.line(org, d.getId(), today));
    }

    /** The reception board: every doctor's line for today. */
    public List<TokenView> board() {
        access.assertModuleOn();
        Long org = access.org();
        List<QueueToken> all = tokens.board(org, TenantClock.today());
        return views(org, all);
    }

    /** One doctor's line for today (the doctor's screen in S3). */
    public List<TokenView> line(Long providerId) {
        access.assertModuleOn();
        Long org = access.org();
        return views(org, tokens.line(org, providerId, TenantClock.today()));
    }

    public TokenView get(Long id) {
        access.assertModuleOn();
        Long org = access.org();
        QueueToken t = scoped(id, org);
        return views(org, List.of(t)).get(0);
    }

    /**
     * Move a token: call, recall, start, park, resume, complete, cancel, noShow. One conditional UPDATE; when it
     * changes nothing the refusal says why in words ("A-007 is completed — it cannot be called").
     */
    public TokenView move(Long id, String action, String reason) {
        access.assertModuleOn();
        Long org = access.org();
        QueueStatus.Move m = QueueStatus.MOVES.get(action);
        if (m == null) throw new ValidationException("Unknown action: " + action);
        QueueToken t = scoped(id, org);
        if ("park".equals(action) && (reason == null || reason.isBlank())) {
            throw new ValidationException("Say why the patient is parked, e.g. CBC pending.");
        }
        if ("call".equals(action)) {
            // 04c: a patient is with one doctor at a time
            tokens.patientTokens(org, t.getPatientId(), t.getVisitDate(), QueueStatus.WITH_DOCTOR).stream()
                    .filter(o -> !o.getId().equals(t.getId())).findFirst().ifPresent(o -> {
                        throw new ValidationException("This patient is with " + o.getProviderName() + " now (" + o.getTokenLabel() + ").");
                    });
        }
        int changed = writer.transition(id, org, m.from(), m.to(), access.userId(),
                reason == null || reason.isBlank() ? null : reason.trim());
        if (changed == 0) {
            QueueToken now = scoped(id, org);
            throw new ValidationException(now.getTokenLabel() + " is " + QueueStatus.words(now.getStatus())
                    + " — it cannot be " + pastTense(action) + ".");
        }
        audit.record(AuditRecord.builder().action("TOKEN_" + action.toUpperCase(java.util.Locale.ROOT))
                .entityType("TOKEN").entityRef(t.getTokenLabel()).reason(reason).build());
        return get(id);
    }

    /** "Call next": the lowest WAITING token of the doctor's day, claimed atomically; retried if another desk won. */
    public Optional<TokenView> callNext(Long providerId) {
        access.assertModuleOn();
        Long org = access.org();
        LocalDate today = TenantClock.today();
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<QueueToken> next = tokens.findFirstByOrganizationIdAndProviderIdAndVisitDateAndStatusOrderByTokenNoAsc(
                    org, providerId, today, QueueStatus.WAITING);
            if (next.isEmpty()) return Optional.empty();
            try {
                return Optional.of(move(next.get().getId(), "call", null));
            } catch (ValidationException lostOrBusy) {
                // taken by another caller a moment ago, or the patient is with another doctor: try the next one
                if (!tokens.findById(next.get().getId()).map(x -> QueueStatus.WAITING.equals(x.getStatus())).orElse(false)) continue;
                throw lostOrBusy;
            }
        }
        return Optional.empty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private String prefixFor(Long org, Long providerId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ClinicProvider> found = prefixes.findByOrganizationIdAndProviderId(org, providerId);
            if (found.isPresent()) return found.get().getTokenPrefix();
            try {
                return writer.assignPrefix(org, providerId);
            } catch (DataIntegrityViolationException raced) {
                // another desk gave this doctor (or that letter) a row first: read again
            }
        }
        return prefixes.findByOrganizationIdAndProviderId(org, providerId).map(ClinicProvider::getTokenPrefix)
                .orElseThrow(() -> new ValidationException("Could not give this doctor a token letter. Try again."));
    }

    /**
     * The clinic's doctors from appointment-service. A READ, so a transport failure is retried ONCE — the first call
     * after a quiet spell can be slow (S4-lite gate, 2026-10-10: "Read timed out", which reached the screen as a bare
     * 500). If it still fails, the refusal says so in words; it is never a 500.
     */
    private List<Doctor> directoryDoctors() {
        AppointmentDirectoryClient.Envelope<List<Doctor>> r;
        try {
            r = directory.doctors();
        } catch (org.springframework.web.client.RestClientException slowOrDown) {
            try {
                r = directory.doctors();
            } catch (org.springframework.web.client.RestClientException again) {
                throw new ValidationException("The doctor list is not reachable right now. Try again in a moment.");
            }
        }
        if (r == null || !r.isSuccess() || r.getData() == null) {
            throw new ValidationException("The doctor list is not reachable right now. Try again in a moment.");
        }
        return r.getData();
    }

    /** The doctor, from THIS clinic's list — an id of another clinic's doctor answers like a missing one. */
    private Doctor doctorOf(Long providerId) {
        if (providerId == null) throw new ValidationException("Choose the doctor.");
        return directoryDoctors().stream().filter(d -> providerId.equals(d.getId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found."));
    }

    /** The clinic's venue (appointment-service needs one per doctor): the first one, else one is made. */
    private Long clinicVenue() {
        AppointmentDirectoryClient.Envelope<List<AppointmentDirectoryClient.Venue>> vs;
        try {
            vs = directory.venues();
        } catch (org.springframework.web.client.RestClientException down) {
            throw new ValidationException("The clinic could not be set up for doctors right now. Try again in a moment.");
        }
        if (vs != null && vs.isSuccess() && vs.getData() != null && !vs.getData().isEmpty()) return vs.getData().get(0).getId();
        AppointmentDirectoryClient.Envelope<AppointmentDirectoryClient.Venue> made;
        try {
            made = directory.createVenue(AppointmentDirectoryClient.Venue.builder().name("Clinic").build());
        } catch (org.springframework.web.client.RestClientException down) {
            throw new ValidationException("The clinic could not be set up for doctors right now. Try again in a moment.");
        }
        if (made == null || !made.isSuccess() || made.getData() == null) {
            throw new ValidationException("The clinic could not be set up for doctors. Try again in a moment.");
        }
        return made.getData().getId();
    }

    private QueueToken scoped(Long id, Long org) {
        if (id == null) throw new ResourceNotFoundException("Token not found.");
        return tokens.findByIdAndOrganizationId(id, org).orElseThrow(() -> new ResourceNotFoundException("Token not found."));
    }

    private List<TokenView> views(Long org, List<QueueToken> list) {
        Map<Long, Patient> who = new HashMap<>();
        patients.findAllById(list.stream().map(QueueToken::getPatientId).distinct().toList()).stream()
                .filter(p -> org.equals(p.getOrganizationId())).forEach(p -> who.put(p.getId(), p));
        Map<Long, List<QueueToken>> lines = list.stream().collect(Collectors.groupingBy(QueueToken::getProviderId));
        return list.stream().map(t -> view(t, who.get(t.getPatientId()), lines.get(t.getProviderId()))).toList();
    }

    private static TokenView view(QueueToken t, Patient p, List<QueueToken> line) {
        Integer ahead = null;
        if (QueueStatus.WAITING.equals(t.getStatus()) && line != null) {
            ahead = (int) line.stream().filter(o -> QueueStatus.WAITING.equals(o.getStatus()) && o.getTokenNo() < t.getTokenNo()).count();
        }
        return TokenView.builder()
                .id(t.getId()).tokenLabel(t.getTokenLabel()).tokenNo(t.getTokenNo()).status(t.getStatus())
                .patientId(t.getPatientId()).patientName(p == null ? null : p.getName()).mrn(p == null ? null : p.getMrn())
                .providerId(t.getProviderId()).providerName(t.getProviderName()).visitDate(t.getVisitDate())
                .ahead(ahead).parkReason(t.getParkReason()).createdAt(t.getCreatedAt()).calledAt(t.getCalledAt())
                .startedAt(t.getStartedAt()).completedAt(t.getCompletedAt()).version(t.getVersion())
                .build();
    }

    private static String pastTense(String action) {
        return switch (action) {
            case "call" -> "called";
            case "recall" -> "sent back to waiting";
            case "start" -> "started";
            case "park" -> "parked";
            case "resume" -> "resumed";
            case "complete" -> "completed";
            case "cancel" -> "cancelled";
            case "noShow" -> "marked as not here";
            default -> action;
        };
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
