package com.myplus.business_service.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.dto.AddressOptionDTO;
import com.myplus.business_service.entity.Area;
import com.myplus.business_service.entity.City;
import com.myplus.business_service.repository.AreaRepository;
import com.myplus.business_service.repository.CityRepository;

/**
 * SRA-1 — a business's own list of cities and the areas inside them (V82).
 * Design: {@code microservices/docs/slices/sra-sales-by-rep-and-area.md} §4.
 *
 * <h3>Every id from a request is resolved inside the caller's business</h3>
 * An area id arrives in a customer save, a supplier save and the import. Each goes through {@link #areaInOrg}, so an
 * id belonging to another business reads as "not found" — the same answer as an id that does not exist.
 *
 * <h3>A duplicate is refused, and the existing row is handed back</h3>
 * Uniqueness is the database's (V82's case-insensitive UNIQUE keys), so two clicks at once cannot make two
 * "Zahir Pir"s. The look-up first is only to answer with the row that already exists, which the inline "+ New"
 * then selects — the operator meant that area, and now has it.
 */
@Service
public class AddressService {

    static final int MAX_NAME = 80;
    static final String ACTIVE = "ACTIVE";

    private final CityRepository cities;
    private final AreaRepository areas;

    public AddressService(CityRepository cities, AreaRepository areas) {
        this.cities = cities;
        this.areas = areas;
    }

    /** The outcome of an add: the row, and whether it was made now or already existed. */
    public record Added(AddressOptionDTO row, boolean created) {}

    @Transactional(readOnly = true)
    public List<AddressOptionDTO> cities(Long orgId) {
        return cities.findByOrg(orgId).stream().map(AddressService::view).toList();
    }

    /** Every area of the business with its city's name — one read fills the picker and labels every grid row. */
    @Transactional(readOnly = true)
    public List<AddressOptionDTO> areas(Long orgId) {
        Map<Long, String> cityNames = new HashMap<>();
        for (City c : cities.findByOrg(orgId)) cityNames.put(c.getId(), c.getName());
        return areas.findByOrg(orgId).stream().map(a -> view(a, cityNames.get(a.getCityId()))).toList();
    }

    /*
     * ⚠ NOT @Transactional, on purpose. The race fallback below catches the UNIQUE-key violation and reads the row that
     * won. Inside a surrounding transaction the failed insert marks it rollback-only, so the read would succeed and the
     * commit would then fail. Without one, saveAndFlush runs in the repository's own transaction, which alone rolls back.
     */
    public Added addCity(Long orgId, Long userId, String rawName) {
        String name = clean(rawName, "City");
        Optional<City> existing = cities.findByName(orgId, name);
        if (existing.isPresent()) return new Added(view(existing.get()), false);
        try {
            City c = cities.saveAndFlush(City.builder().organizationId(orgId).name(name).userId(userId).build());
            return new Added(view(c), true);
        } catch (DataIntegrityViolationException raced) {
            // Two adds of the same name at once: the UNIQUE key let exactly one through. Answer with it.
            return cities.findByName(orgId, name).map(c -> new Added(view(c), false)).orElseThrow(() -> raced);
        }
    }

    /** Not @Transactional — the same reason as {@link #addCity}. */
    public Added addArea(Long orgId, Long userId, Long cityId, String rawName) {
        String name = clean(rawName, "Area");
        City city = (cityId == null) ? null : cities.findByIdScoped(cityId, orgId).orElse(null);
        if (city == null) throw new IllegalArgumentException("Choose the city first.");
        if (!ACTIVE.equalsIgnoreCase(city.getStatus())) throw new IllegalArgumentException("That city is no longer in use.");
        Optional<Area> existing = areas.findByName(orgId, city.getId(), name);
        if (existing.isPresent()) return new Added(view(existing.get(), city.getName()), false);
        try {
            Area a = areas.saveAndFlush(Area.builder().organizationId(orgId).cityId(city.getId()).name(name).userId(userId).build());
            return new Added(view(a, city.getName()), true);
        } catch (DataIntegrityViolationException raced) {
            return areas.findByName(orgId, city.getId(), name).map(a -> new Added(view(a, city.getName()), false))
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * The area an id names, ONLY if it belongs to this business — else empty. The single check every writer of
     * {@code area_id} goes through (customer save, supplier save, import).
     */
    @Transactional(readOnly = true)
    public Optional<AddressOptionDTO> areaInOrg(Long areaId, Long orgId) {
        if (areaId == null || orgId == null) return Optional.empty();
        return areas.findByIdScoped(areaId, orgId).map(a -> view(a,
                cities.findByIdScoped(a.getCityId(), orgId).map(City::getName).orElse(null)));
    }

    /**
     * Import (A4): the area named by a city and an area, made if either is new. Blank area = none (a city alone
     * files nothing — a customer is filed under an AREA). The counts let the import summary say what it created, so
     * a typo becomes a visible new row rather than a silent one.
     */
    /** Not @Transactional — the same reason as {@link #addCity}. */
    public Optional<AddressOptionDTO> resolveOrCreate(Long orgId, Long userId, String cityName, String areaName, int[] createdCounts) {
        if (areaName == null || areaName.isBlank() || cityName == null || cityName.isBlank()) return Optional.empty();
        Added city = addCity(orgId, userId, cityName);
        if (city.created() && createdCounts != null) createdCounts[0]++;
        Added area = addArea(orgId, userId, city.row().getId(), areaName);
        if (area.created() && createdCounts != null) createdCounts[1]++;
        return Optional.of(area.row());
    }

    static String clean(String raw, String what) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new IllegalArgumentException(what + " name is required.");
        if (name.length() > MAX_NAME) throw new IllegalArgumentException(what + " name is too long (" + MAX_NAME + " characters at most).");
        return name;
    }

    private static AddressOptionDTO view(City c) {
        return AddressOptionDTO.builder().id(c.getId()).name(c.getName()).status(c.getStatus()).build();
    }

    private static AddressOptionDTO view(Area a, String cityName) {
        return AddressOptionDTO.builder().id(a.getId()).name(a.getName()).status(a.getStatus())
                .cityId(a.getCityId()).cityName(cityName).build();
    }
}
