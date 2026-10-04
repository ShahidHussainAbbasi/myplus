package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.myplus.commerce.contracts.client.SchedulingClient;
import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.MeetingEvent;
import com.myplus.education.entity.MeetingEventStatus;
import com.myplus.education.repository.MeetingEventRepository;
import com.myplus.education.repository.StaffRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * SCHED-2 — a scheduling call that does not answer is asked once more, then the school is told the truth.
 *
 * <p>Publishing an evening's slots timed out (3 s read timeout) while appointment-service finished the work; the
 * school saw ERROR and a raw I/O message for slots that existed. Every call on the client is idempotent on the
 * other side by a UNIQUE key, so a second try is safe and its answer is the real one.
 */
@ExtendWith(MockitoExtension.class)
class MeetingServiceRetryTest {

    @Mock private MeetingEventRepository eventRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private SchedulingClient schedulingClient;
    @Mock private SettingsService settingsService;

    @InjectMocks private MeetingService service;

    private MeetingEvent evening;

    @BeforeEach
    void setUp() {
        evening = new MeetingEvent();
        evening.setId(7L);
        evening.setStatus(MeetingEventStatus.OPEN);
    }

    private static ResourceAccessException readTimedOut() {
        return new ResourceAccessException("I/O error on POST request: Read timed out");
    }

    @Test
    @DisplayName("publish: a timeout is retried once, and the retry's answer is returned")
    void publishRetriesOnceAndReturnsTheAnswer() {
        Map<String, Object> answer = Map.of("data", Map.of("created", 0, "alreadyExisted", 6));
        when(schedulingClient.generate(eq(3L), any(), eq("EDU-EVT-7"), anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(readTimedOut())
                .thenReturn(answer);

        Map<String, Object> out = service.publishSlots(evening, 3L, "2027-03-15T18:00", "2027-03-15T19:00", 10);

        assertThat(out).isSameAs(answer);   // "alreadyExisted: 6" — the first call HAD published them
        verify(schedulingClient, times(2)).generate(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("publish: two timeouts become an honest refusal — the slots MAY already be published")
    void publishTwiceUnansweredSaysWhatIsKnown() {
        when(schedulingClient.generate(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(readTimedOut());

        assertThatThrownBy(() -> service.publishSlots(evening, 3L, "2027-03-15T18:00", "2027-03-15T19:00", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("may already be published")
                .hasMessageNotContaining("I/O error");
        verify(schedulingClient, times(2)).generate(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("book: a timeout is retried once (the core books a family once per slot)")
    void bookRetriesOnce() {
        when(schedulingClient.book(eq(11L), eq(5L), eq("EDU-EVT-7")))
                .thenThrow(readTimedOut())
                .thenReturn(Map.of("data", Map.of("bookingId", 99, "alreadyBooked", true)));

        Map<String, Object> out = service.book(evening, 11L, 5L);

        assertThat(out).containsEntry("alreadyBooked", true);
        verify(schedulingClient, times(2)).book(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("an answer that is NOT a timeout is not retried — a 500 is the core's verdict, not silence")
    void serverErrorIsNotRetried() {
        when(schedulingClient.generate(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.publishSlots(evening, 3L, "2027-03-15T18:00", "2027-03-15T19:00", 10))
                .isInstanceOf(HttpServerErrorException.class);
        verify(schedulingClient, times(1)).generate(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }
}
