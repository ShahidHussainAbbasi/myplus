package com.myplus.analytics.service;

import com.myplus.analytics.dto.DashboardWidgetDTO;
import com.myplus.analytics.entity.DashboardWidget;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.analytics.repository.DashboardWidgetRepository;
import com.myplus.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class DashboardService {

    private final DashboardWidgetRepository widgetRepo;
    private final ModelMapper modelMapper = new ModelMapper();

    @Transactional(readOnly = true)
    public List<DashboardWidgetDTO> getUserWidgets(Long userId) {
        return widgetRepo.findByUserIdAndIsActiveTrueOrderByPosition(userId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public DashboardWidgetDTO addWidget(Long userId, DashboardWidgetDTO dto) {
        DashboardWidget w = DashboardWidget.builder()
                .userId(userId)
                .widgetType(dto.getWidgetType())
                .title(dto.getTitle())
                .dataSource(dto.getDataSource())
                .config(dto.getConfig())
                .position(dto.getPosition())
                .isActive(true)
                .build();
        return toDto(widgetRepo.save(w));
    }

    public DashboardWidgetDTO updateWidget(Long widgetId, DashboardWidgetDTO dto) {
        DashboardWidget w = ownWidgetOrThrow(widgetId);
        w.setWidgetType(dto.getWidgetType());
        w.setTitle(dto.getTitle());
        w.setDataSource(dto.getDataSource());
        w.setConfig(dto.getConfig());
        w.setPosition(dto.getPosition());
        w.setActive(dto.isActive());
        return toDto(widgetRepo.save(w));
    }

    public void removeWidget(Long widgetId) {
        widgetRepo.delete(ownWidgetOrThrow(widgetId));
    }

    public void reorderWidgets(Long userId, List<Long> widgetIds) {
        // Bind to the authenticated caller, never a client-supplied userId — a widget is personal.
        Long caller = CurrentUser.userId();
        for (int i = 0; i < widgetIds.size(); i++) {
            DashboardWidget w = widgetRepo.findById(widgetIds.get(i)).orElse(null);
            if (w != null && caller != null && caller.equals(w.getUserId())) {
                w.setPosition(i);
                widgetRepo.save(w);
            }
        }
    }

    /** A widget is personal: update/remove are allowed only on the caller's OWN widgets. Previously either
     *  took a raw widgetId with no owner check, so any user could edit or delete another user's widget. */
    private DashboardWidget ownWidgetOrThrow(Long widgetId) {
        DashboardWidget w = widgetRepo.findById(widgetId)
                .orElseThrow(() -> new ResourceNotFoundException("Widget not found: " + widgetId));
        Long caller = CurrentUser.userId();
        if (caller == null || !caller.equals(w.getUserId()))
            throw new ResourceNotFoundException("Widget not found: " + widgetId);   // don't confirm it exists
        return w;
    }

    private DashboardWidgetDTO toDto(DashboardWidget w) {
        return modelMapper.map(w, DashboardWidgetDTO.class);
    }
}
