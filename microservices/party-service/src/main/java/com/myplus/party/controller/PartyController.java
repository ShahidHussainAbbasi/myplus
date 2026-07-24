package com.myplus.party.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.myplus.party.dto.PartyDTO;
import com.myplus.party.service.PartyService;

import lombok.RequiredArgsConstructor;

/**
 * The party/contact master API. Mapped at the full {@code /api/party/...} path (gateway routes {@code /api/party/**}
 * here, no StripPrefix). CRUD for the contact screen; {@code upsert} is the bridge modules call on write (find-or-
 * create by de-dup key); {@code lookup} matches an existing party. Org-scoped inside the service.
 */
@RestController
@RequestMapping("/api/party/parties")
@RequiredArgsConstructor
public class PartyController {

    private final PartyService service;

    @GetMapping
    public List<PartyDTO> list(@RequestParam(required = false) String q) {
        return service.search(q);
    }

    @GetMapping("/{id}")
    public PartyDTO get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public PartyDTO create(@RequestBody PartyDTO dto) {
        return service.create(dto);
    }

    @PutMapping("/{id}")
    public PartyDTO update(@PathVariable Long id, @RequestBody PartyDTO dto) {
        return service.update(id, dto);
    }

    /** Bridge entry point: find-or-create a party by de-dup key (contact, then email) and return it (with its id). */
    @PostMapping("/upsert")
    public PartyDTO upsert(@RequestBody PartyDTO dto) {
        return service.upsert(dto);
    }
}
