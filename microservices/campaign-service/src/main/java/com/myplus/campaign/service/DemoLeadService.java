package com.myplus.campaign.service;

import com.myplus.campaign.dto.DemoRequestRequest;
import com.myplus.campaign.entity.DemoRequest;
import com.myplus.campaign.repository.DemoRequestRepository;
import com.myplus.common.notify.EmailRequest;
import com.myplus.common.notify.NotificationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** Persists a Book-a-Demo lead and notifies the team + acknowledges the requester (best-effort email via
 *  notification-service). */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoLeadService {

    private final DemoRequestRepository repository;
    private final NotificationClient notificationClient;

    @Value("${app.support-email:maxtheservice@gmail.com}")
    private String supportEmail;

    @Transactional
    public void submit(final DemoRequestRequest dto) {
        final DemoRequest e = repository.save(DemoRequest.builder()
                .fullName(trim(dto.getFullName())).workEmail(trim(dto.getWorkEmail()))
                .company(trim(dto.getCompany())).country(trim(dto.getCountry())).phone(trim(dto.getPhone()))
                .interest(trim(dto.getInterest())).companySize(trim(dto.getCompanySize()))
                .message(trim(dto.getMessage())).timezone(trim(dto.getTimezone()))
                .preferredDate(trim(dto.getPreferredDate())).locale(trim(dto.getLocale()))
                .source(trim(dto.getSource())).status("NEW").build());
        log.info("Demo lead saved (id={}, company={}, country={})", e.getId(), e.getCompany(), e.getCountry());

        try {
            notificationClient.sendEmail(teamEmail(e));
        } catch (Exception ex) {
            log.error("Demo lead {}: team notification failed: {}", e.getId(), ex.getMessage());
        }
        try {
            notificationClient.sendEmail(ackEmail(e));
        } catch (Exception ex) {
            log.error("Demo lead {}: acknowledgement failed: {}", e.getId(), ex.getMessage());
        }
    }

    private EmailRequest teamEmail(final DemoRequest d) {
        final StringBuilder b = new StringBuilder("A new demo request was submitted from the website.\n\n");
        line(b, "Name", d.getFullName());
        line(b, "Work email", d.getWorkEmail());
        line(b, "Company", d.getCompany());
        line(b, "Country", d.getCountry());
        line(b, "Phone", d.getPhone());
        line(b, "Interested in", d.getInterest());
        line(b, "Company size", d.getCompanySize());
        line(b, "Preferred date", d.getPreferredDate());
        line(b, "Timezone", d.getTimezone());
        line(b, "Locale", d.getLocale());
        line(b, "Source", d.getSource());
        if (StringUtils.hasText(d.getMessage())) {
            b.append("\nMessage:\n").append(d.getMessage()).append('\n');
        }
        b.append("\nLead reference: #").append(d.getId());
        return EmailRequest.builder()
                .to(List.of(supportEmail))
                .replyTo(d.getWorkEmail())
                .subject("New demo request — " + d.getCompany() + " (" + d.getCountry() + ")")
                .body(b.toString())
                .build();
    }

    private EmailRequest ackEmail(final DemoRequest d) {
        final StringBuilder b = new StringBuilder("Hi ").append(firstName(d.getFullName())).append(",\n\n");
        b.append("Thank you for requesting a demo of MaxTheService. We have received your request");
        if (StringUtils.hasText(d.getCompany())) b.append(" for ").append(d.getCompany());
        b.append(" and a member of our team will reach out within one business day to schedule a "
                + "30-minute walkthrough at a time that suits your timezone");
        if (StringUtils.hasText(d.getTimezone())) b.append(" (").append(d.getTimezone()).append(")");
        b.append(".\n\nIf you need anything sooner, just reply to this email or write to ")
                .append(supportEmail).append(".\n\nWarm regards,\nThe MaxTheService Team\nhttps://maxtheservice.com");
        return EmailRequest.builder()
                .to(List.of(d.getWorkEmail()))
                .subject("Thanks for your interest in MaxTheService")
                .body(b.toString())
                .build();
    }

    private static void line(final StringBuilder b, final String label, final String value) {
        b.append(label).append(": ").append(StringUtils.hasText(value) ? value : "—").append('\n');
    }

    private static String firstName(final String fullName) {
        if (!StringUtils.hasText(fullName)) return "there";
        final String f = fullName.trim().split("\\s+")[0];
        return f.isEmpty() ? "there" : f;
    }

    private static String trim(final String s) {
        return s == null ? null : s.trim();
    }
}
