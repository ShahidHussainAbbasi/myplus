package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.PartySetOffAlloc;

public interface PartySetOffAllocRepo extends JpaRepository<PartySetOffAlloc, Long> {

    @Query("select a from PartySetOffAlloc a where a.setOffId = :setOffId order by a.id")
    List<PartySetOffAlloc> findBySetOff(@Param("setOffId") Long setOffId);
}
