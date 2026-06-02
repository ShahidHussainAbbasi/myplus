package com.myplus.campaign.service;

import com.myplus.campaign.dto.TemplateDTO;
import com.myplus.campaign.entity.Template;
import com.myplus.campaign.exception.ResourceNotFoundException;
import com.myplus.campaign.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final ModelMapper modelMapper = new ModelMapper();

    public TemplateDTO createTemplate(TemplateDTO dto) {
        Template t = modelMapper.map(dto, Template.class);
        t.setId(null);
        return toDto(templateRepository.save(t));
    }

    public TemplateDTO updateTemplate(Long id, TemplateDTO dto) {
        Template t = findOrThrow(id);
        t.setName(dto.getName());
        t.setType(dto.getType());
        t.setSubject(dto.getSubject());
        t.setHtmlContent(dto.getHtmlContent());
        t.setTextContent(dto.getTextContent());
        t.setActive(dto.isActive());
        return toDto(templateRepository.save(t));
    }

    @Transactional(readOnly = true)
    public TemplateDTO getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<TemplateDTO> getAll(Pageable pageable) {
        return templateRepository.findAll(pageable).map(this::toDto);
    }

    public void deleteTemplate(Long id) {
        Template t = findOrThrow(id);
        templateRepository.delete(t);
    }

    @Transactional(readOnly = true)
    public List<TemplateDTO> getByType(String type) {
        Template.Type t = Template.Type.valueOf(type.toUpperCase());
        return templateRepository.findByTypeAndIsActiveTrue(t).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private Template findOrThrow(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found: " + id));
    }

    private TemplateDTO toDto(Template t) {
        return modelMapper.map(t, TemplateDTO.class);
    }
}
