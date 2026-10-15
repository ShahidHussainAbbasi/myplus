package com.myplus.clinical.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.clinical.dto.ConsultDtos.RxLine;
import com.myplus.clinical.dto.ConsultDtos.TemplateRequest;
import com.myplus.clinical.dto.ConsultDtos.TemplateView;
import com.myplus.clinical.entity.RxTemplate;
import com.myplus.clinical.entity.RxTemplateItem;
import com.myplus.clinical.repository.RxTemplateItemRepo;
import com.myplus.clinical.repository.RxTemplateRepo;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * HMS S3b-2 — the clinic's prescription templates (design §4e). A template is a STARTING POINT: Use copies its lines
 * onto the screen, where the doctor adjusts them; nothing reaches a visit until Save / Submit. Lines are checked by
 * the same rules as a visit's ({@link EncounterRules#rxLines}). Doctor-only, like the rest of the consultation.
 */
@Service
@RequiredArgsConstructor
public class RxTemplateService {

    public static final int MAX_ACTIVE = 200;

    private final RxTemplateRepo templates;
    private final RxTemplateItemRepo items;
    private final ClinicAccess access;

    private Long guard() {
        access.assertModuleOn();
        access.assertCanConsult();
        return access.org();
    }

    @Transactional(readOnly = true)
    public List<TemplateView> list() {
        Long org = guard();
        List<RxTemplate> all = templates.findByOrganizationIdAndStatusOrderByNameAsc(org, RxTemplate.ACTIVE);
        if (all.isEmpty()) return List.of();
        Map<Long, List<RxLine>> lines = items.itemsOf(org, all.stream().map(RxTemplate::getId).toList()).stream()
                .collect(Collectors.groupingBy(RxTemplateItem::getTemplateId,
                        Collectors.mapping(RxTemplateService::line, Collectors.toList())));
        return all.stream().map(t -> TemplateView.builder().id(t.getId()).name(t.getName())
                .lines(lines.getOrDefault(t.getId(), List.of())).build()).toList();
    }

    @Transactional
    public TemplateView create(TemplateRequest req) {
        Long org = guard();
        String name = name(req == null ? null : req.getName());
        List<RxLine> lines = EncounterRules.rxLines(req.getLines());
        if (lines.isEmpty()) throw new ValidationException("Add the medicines first, then save them as a template.");
        String key = name.toLowerCase(Locale.ROOT);
        if (templates.existsByOrganizationIdAndNameKey(org, key)) throw taken(name);
        if (templates.countByOrganizationIdAndStatus(org, RxTemplate.ACTIVE) >= MAX_ACTIVE) {
            throw new ValidationException("The clinic has " + MAX_ACTIVE + " templates. Remove one you no longer use first.");
        }
        RxTemplate t = new RxTemplate();
        t.setOrganizationId(org);
        t.setName(name);
        t.setNameKey(key);
        t.setCreatedBy(access.userId());
        t.setCreatedAt(LocalDateTime.now());
        try {
            t = templates.saveAndFlush(t);
        } catch (DataIntegrityViolationException raced) {
            // two doctors saving the same name at the same moment: uq_rx_template_name decides, in words
            throw taken(name);
        }
        int n = 0;
        for (RxLine l : lines) {
            RxTemplateItem i = new RxTemplateItem();
            i.setOrganizationId(org);
            i.setTemplateId(t.getId());
            i.setLineNo(++n);
            i.setProductId(l.getProductId());
            i.setMedicineName(l.getMedicineName());
            i.setQuantity(Integer.valueOf(l.getQuantity()));
            i.setDosage(l.getDosage());
            i.setFrequency(l.getFrequency());
            i.setDuration(l.getDuration());
            items.save(i);
        }
        return TemplateView.builder().id(t.getId()).name(t.getName()).lines(lines).build();
    }

    /** Retire: off the list, its name free again; the rows stay. */
    @Transactional
    public void retire(Long id) {
        Long org = guard();
        RxTemplate t = templates.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found."));
        if (RxTemplate.RETIRED.equals(t.getStatus())) return;
        t.setStatus(RxTemplate.RETIRED);
        t.setNameKey(null);
        t.setRetiredAt(LocalDateTime.now());
        templates.save(t);
    }

    static String name(String raw) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) throw new ValidationException("Give the template a name, e.g. Fever + Flu.");
        if (t.length() > 80) throw new ValidationException("A template name is at most 80 characters.");
        return t;
    }

    private static ValidationException taken(String name) {
        return new ValidationException("A template called '" + name + "' exists already. Choose another name.");
    }

    private static RxLine line(RxTemplateItem i) {
        return RxLine.builder().productId(i.getProductId()).medicineName(i.getMedicineName())
                .quantity(String.valueOf(i.getQuantity())).dosage(i.getDosage()).frequency(i.getFrequency())
                .duration(i.getDuration()).build();
    }
}
