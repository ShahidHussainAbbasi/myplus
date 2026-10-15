package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.myplus.clinical.dto.ConsultDtos.RxLine;
import com.myplus.clinical.dto.ConsultDtos.TemplateRequest;
import com.myplus.clinical.entity.RxTemplate;
import com.myplus.clinical.entity.RxTemplateItem;
import com.myplus.clinical.repository.RxTemplateItemRepo;
import com.myplus.clinical.repository.RxTemplateRepo;

/** HMS S3b-2 — templates: doctor-only, a unique name per clinic, the same line rules as a visit, retire frees the name. */
class RxTemplateServiceTest {

    RxTemplateRepo templates; RxTemplateItemRepo items; ClinicAccess access;
    RxTemplateService service;

    @BeforeEach
    void setUp() {
        templates = mock(RxTemplateRepo.class); items = mock(RxTemplateItemRepo.class); access = mock(ClinicAccess.class);
        service = new RxTemplateService(templates, items, access);
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(70L);
        when(templates.saveAndFlush(any(RxTemplate.class))).thenAnswer(inv -> { RxTemplate t = inv.getArgument(0); t.setId(40L); return t; });
    }

    private static RxLine line(Long productId, String name, String qty) {
        return RxLine.builder().productId(productId).medicineName(name).quantity(qty).dosage("1+0+1").frequency("BD").duration("5 days").build();
    }

    @Test
    void the_front_desk_cannot_read_or_write_templates() {
        doThrow(new org.springframework.security.access.AccessDeniedException("Only a doctor can open clinical records."))
                .when(access).assertCanConsult();
        assertThatThrownBy(() -> service.list()).hasMessageContaining("Only a doctor");
        assertThatThrownBy(() -> service.create(TemplateRequest.builder().name("Fever").lines(List.of(line(1L, "Panadol", "10"))).build()))
                .hasMessageContaining("Only a doctor");
        verify(templates, never()).saveAndFlush(any());
    }

    @Test
    void a_template_is_saved_with_its_lines_and_a_tidy_name() {
        var v = service.create(TemplateRequest.builder().name("  Fever   +  Flu ")
                .lines(List.of(line(1L, "Panadol", "10"), line(2L, "Amoxil", "15"))).build());
        assertThat(v.getName()).isEqualTo("Fever + Flu");
        verify(templates).existsByOrganizationIdAndNameKey(7L, "fever + flu");
        verify(items, times(2)).save(any(RxTemplateItem.class));
    }

    @Test
    void the_same_name_in_any_case_is_refused_and_so_is_a_race_for_it() {
        when(templates.existsByOrganizationIdAndNameKey(7L, "fever + flu")).thenReturn(true);
        assertThatThrownBy(() -> service.create(TemplateRequest.builder().name("FEVER + FLU").lines(List.of(line(1L, "Panadol", "10"))).build()))
                .hasMessageContaining("exists already");
        when(templates.existsByOrganizationIdAndNameKey(7L, "cough")).thenReturn(false);
        when(templates.saveAndFlush(any(RxTemplate.class))).thenThrow(new DataIntegrityViolationException("uq_rx_template_name"));
        assertThatThrownBy(() -> service.create(TemplateRequest.builder().name("Cough").lines(List.of(line(1L, "Panadol", "10"))).build()))
                .hasMessageContaining("exists already");
    }

    @Test
    void lines_follow_the_visit_rules_and_an_empty_template_is_refused() {
        assertThatThrownBy(() -> service.create(TemplateRequest.builder().name("X").lines(List.of(line(null, "Typed", "1"))).build()))
                .hasMessageContaining("not from the pharmacy's list");
        assertThatThrownBy(() -> service.create(TemplateRequest.builder().name("X").lines(List.of()).build()))
                .hasMessageContaining("Add the medicines first");
        assertThatThrownBy(() -> service.create(TemplateRequest.builder().name(" ").lines(List.of(line(1L, "Panadol", "1"))).build()))
                .hasMessageContaining("Give the template a name");
    }

    @Test
    void the_clinic_has_at_most_200() {
        when(templates.countByOrganizationIdAndStatus(7L, RxTemplate.ACTIVE)).thenReturn(200L);
        assertThatThrownBy(() -> service.create(TemplateRequest.builder().name("One more").lines(List.of(line(1L, "Panadol", "1"))).build()))
                .hasMessageContaining("200 templates");
    }

    @Test
    void retire_frees_the_name_and_another_clinics_template_is_not_found() {
        RxTemplate t = new RxTemplate(); t.setId(40L); t.setOrganizationId(7L); t.setName("Fever"); t.setNameKey("fever");
        when(templates.findByIdAndOrganizationId(40L, 7L)).thenReturn(Optional.of(t));
        service.retire(40L);
        assertThat(t.getStatus()).isEqualTo(RxTemplate.RETIRED);
        assertThat(t.getNameKey()).isNull();
        when(templates.findByIdAndOrganizationId(41L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retire(41L)).hasMessage("Template not found.");
    }

    @Test
    void the_list_carries_each_templates_lines() {
        RxTemplate t = new RxTemplate(); t.setId(40L); t.setOrganizationId(7L); t.setName("Fever");
        RxTemplateItem i = new RxTemplateItem(); i.setTemplateId(40L); i.setProductId(1L); i.setMedicineName("Panadol"); i.setQuantity(10);
        when(templates.findByOrganizationIdAndStatusOrderByNameAsc(7L, RxTemplate.ACTIVE)).thenReturn(List.of(t));
        when(items.itemsOf(eq(7L), anyCollection())).thenReturn(List.of(i));
        var list = service.list();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getLines()).extracting(RxLine::getMedicineName).containsExactly("Panadol");
    }
}
