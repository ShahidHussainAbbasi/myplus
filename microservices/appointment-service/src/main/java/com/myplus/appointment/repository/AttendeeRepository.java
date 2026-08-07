package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Attendee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttendeeRepository extends JpaRepository<Attendee, Long> {
    List<Attendee> findByOrganizationId(Long organizationId);
    Optional<Attendee> findFirstByPhoneAndOrganizationId(String phone, Long organizationId);
}
