package com.myplus.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;

import com.myplus.campaign.dto.CampaignDTO;
import com.myplus.campaign.entity.Campaign;
import com.myplus.campaign.repository.CampaignRepository;
import com.myplus.common.security.time.TenantClock;

/**
 * H6 C4 — a campaign's schedule is TYPED in the business's zone and stored as the UTC instant (Mailchimp / Klaviyo
 * schedule in the account's zone). Before, the typed wall time was stored as if it were UTC, so a send scheduled for
 * 09:00 Karachi would have fired at 14:00.
 */
class CampaignScheduleTest {

    private final CampaignRepository repo = mock(CampaignRepository.class);
    private final CampaignService service = new CampaignService(repo, new ModelMapper());
    private Campaign stored;

    @BeforeEach
    void setUp() {
        TenantClock.configureDefaultZone("Asia/Karachi");
        stored = new Campaign();
        stored.setId(7L);
        when(repo.findByIdScoped(any(), any(), any())).thenAnswer(inv -> Optional.of(stored));
        when(repo.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void scheduling_09_00_karachi_stores_04_00_utc() {
        service.scheduleCampaign(7L, LocalDateTime.of(2026, 10, 12, 9, 0));
        assertThat(stored.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 4, 0));
        assertThat(stored.getStatus()).isEqualTo(Campaign.Status.SCHEDULED);
    }

    @Test
    void an_update_with_a_new_typed_time_converts_it() {
        stored.setScheduledAt(LocalDateTime.of(2026, 10, 12, 4, 0));
        CampaignDTO dto = new CampaignDTO();
        dto.setScheduledAt(LocalDateTime.of(2026, 10, 13, 10, 30));   // typed: 10:30 Karachi
        service.updateCampaign(7L, dto);
        assertThat(stored.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 5, 30));
    }

    @Test
    void an_update_that_sends_back_the_stored_time_does_not_shift_it_again() {
        // a client that loaded the campaign (UTC) and saved it unchanged must not move it 5 h on every save
        stored.setScheduledAt(LocalDateTime.of(2026, 10, 12, 4, 0));
        CampaignDTO dto = new CampaignDTO();
        dto.setScheduledAt(LocalDateTime.of(2026, 10, 12, 4, 0));
        service.updateCampaign(7L, dto);
        service.updateCampaign(7L, dto);
        assertThat(stored.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 4, 0));
    }

    @Test
    void no_schedule_stays_no_schedule() {
        stored.setScheduledAt(LocalDateTime.of(2026, 10, 12, 4, 0));
        CampaignDTO dto = new CampaignDTO();
        service.updateCampaign(7L, dto);
        assertThat(stored.getScheduledAt()).isNull();
    }
}
