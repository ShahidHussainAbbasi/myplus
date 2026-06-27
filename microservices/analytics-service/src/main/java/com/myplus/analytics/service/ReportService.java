package com.myplus.analytics.service;

import com.myplus.analytics.dto.ReportDefinitionDTO;
import com.myplus.analytics.dto.ReportExecutionDTO;
import com.myplus.analytics.entity.ReportDefinition;
import com.myplus.analytics.entity.ReportExecution;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.analytics.repository.ReportDefinitionRepository;
import com.myplus.analytics.repository.ReportExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReportService {

    private final ReportDefinitionRepository definitionRepo;
    private final ReportExecutionRepository executionRepo;
    private final ModelMapper modelMapper = new ModelMapper();

    public ReportDefinitionDTO createReport(ReportDefinitionDTO dto) {
        ReportDefinition r = modelMapper.map(dto, ReportDefinition.class);
        r.setId(null);
        return toDto(definitionRepo.save(r));
    }

    public ReportExecutionDTO executeReport(Long reportId) {
        ReportDefinition r = definitionRepo.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found: " + reportId));
        ReportExecution exec = ReportExecution.builder()
                .report(r)
                .status(ReportExecution.Status.RUNNING)
                .startedAt(LocalDateTime.now())
                .createdBy(r.getCreatedBy())
                .build();
        exec = executionRepo.save(exec);
        try {
            // Stub: in a real impl this would run the query/aggregation
            String result = "{\"reportId\":" + reportId + ",\"executedAt\":\"" + LocalDateTime.now() + "\"}";
            exec.setResultData(result);
            exec.setStatus(ReportExecution.Status.COMPLETED);
            exec.setCompletedAt(LocalDateTime.now());
            r.setLastRunAt(LocalDateTime.now());
            definitionRepo.save(r);
        } catch (Exception ex) {
            exec.setStatus(ReportExecution.Status.FAILED);
            exec.setErrorMessage(ex.getMessage());
            exec.setCompletedAt(LocalDateTime.now());
        }
        return toExecutionDto(executionRepo.save(exec));
    }

    public ReportDefinitionDTO updateReport(Long id, ReportDefinitionDTO dto) {
        ReportDefinition r = findOrThrow(id);
        r.setName(dto.getName());
        r.setDescription(dto.getDescription());
        r.setType(dto.getType());
        r.setScheduleType(dto.getScheduleType());
        r.setActive(dto.isActive());
        return toDto(definitionRepo.save(r));
    }

    @Transactional(readOnly = true)
    public ReportDefinitionDTO getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<ReportDefinitionDTO> getAll(Long userId, Pageable pageable) {
        Page<ReportDefinition> page = userId != null
                ? definitionRepo.findByCreatedByAndIsActiveTrue(userId, pageable)
                : definitionRepo.findAll(pageable);
        return page.map(this::toDto);
    }

    public void deleteReport(Long id) {
        definitionRepo.delete(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<ReportExecutionDTO> getExecutions(Long reportId, Pageable pageable) {
        return executionRepo.findByReportId(reportId, pageable).map(this::toExecutionDto);
    }

    @Transactional(readOnly = true)
    public ReportExecutionDTO getLatestExecution(Long reportId) {
        Optional<ReportExecution> exec = executionRepo.findTopByReportIdOrderByStartedAtDesc(reportId);
        return exec.map(this::toExecutionDto)
                .orElseThrow(() -> new ResourceNotFoundException("No executions found for report: " + reportId));
    }

    @Transactional(readOnly = true)
    public ReportExecutionDTO getExecutionById(Long execId) {
        ReportExecution e = executionRepo.findById(execId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + execId));
        return toExecutionDto(e);
    }

    private ReportDefinition findOrThrow(Long id) {
        return definitionRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found: " + id));
    }

    private ReportDefinitionDTO toDto(ReportDefinition r) {
        return modelMapper.map(r, ReportDefinitionDTO.class);
    }

    private ReportExecutionDTO toExecutionDto(ReportExecution e) {
        return ReportExecutionDTO.builder()
                .id(e.getId())
                .reportId(e.getReport() != null ? e.getReport().getId() : null)
                .status(e.getStatus())
                .startedAt(e.getStartedAt())
                .completedAt(e.getCompletedAt())
                .resultData(e.getResultData())
                .errorMessage(e.getErrorMessage())
                .createdBy(e.getCreatedBy())
                .build();
    }
}
