package com.myplus.business_service.service;

import com.myplus.business_service.entity.Store;
import com.myplus.business_service.repository.StoreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Store registry operations (multi-location Pattern A). Org-scoped reads; simple create/update. */
@Service
@Transactional
public class StoreService {

    @Autowired
    private StoreRepository storeRepository;

    @Transactional(readOnly = true)
    public List<Store> findScoped(Long orgId, Long userId) {
        return storeRepository.findScoped(orgId, userId);
    }

    @Transactional(readOnly = true)
    public Optional<Store> findById(Long id) {
        return storeRepository.findById(id);
    }

    public Store save(Store store) {
        return storeRepository.save(store);
    }
}
