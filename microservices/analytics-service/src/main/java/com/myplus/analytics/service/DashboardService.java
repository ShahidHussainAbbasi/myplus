package com.myplus.analytics.service;

import com.myplus.analytics.dto.DashboardWidgetDTO;
import com.myplus.analytics.entity.DashboardWidget;
import com.myplus.analytics.exception.ResourceNotFoundException;
import com.myplus.analytics.repository.DashboardWidgetRepository;
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
        DashboardWidget w = widgetRepo.findById(widgetId)
                .orElseThrow(() -> new ResourceNotFoundException("Widget not found: " + widgetId));
        w.setWidgetType(dto.getWidgetType());
        w.setTitle(dto.getTitle());
        w.setDataSource(dto.getDataSource());
        w.setConfig(dto.getConfig());
        w.setPosition(dto.getPosition());
        w.setActive(dto.isActive());
        return toDto(widgetRepo.save(w));
    }

    public void removeWidget(Long widgetId) {
        DashboardWidget w = widgetRepo.findById(widgetId)
                .orElseThrow(() -> new ResourceNotFoundException("Widget not found: " + widgetId));
        widgetRepo.delete(w);
    }

    public void reorderWidgets(Long userId, List<Long> widgetIds) {
        for (int i = 0; i < widgetIds.size(); i++) {
            Long id = widgetIds.get(i);
            DashboardWidget w = widgetRepo.findById(id).orElse(null);
            if (w != null && userId.equals(w.getUserId())) {
                w.setPosition(i);
                widgetRepo.save(w);
            }
        }
    }

    private DashboardWidgetDTO toDto(DashboardWidget w) {
        return modelMapper.map(w, DashboardWidgetDTO.class);
    }
}
