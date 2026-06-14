package com.myplus.campaign.service;

import com.myplus.campaign.dto.AudienceDTO;
import com.myplus.campaign.dto.AudienceMemberDTO;
import com.myplus.campaign.entity.Audience;
import com.myplus.campaign.entity.AudienceMember;
import com.myplus.campaign.exception.ResourceNotFoundException;
import com.myplus.campaign.repository.AudienceMemberRepository;
import com.myplus.campaign.repository.AudienceRepository;
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
public class AudienceService {

    private final AudienceRepository audienceRepository;
    private final AudienceMemberRepository memberRepository;
    private final ModelMapper modelMapper = new ModelMapper();

    public AudienceDTO createAudience(AudienceDTO dto) {
        Audience a = modelMapper.map(dto, Audience.class);
        a.setId(null);
        return toDto(audienceRepository.save(a));
    }

    public AudienceDTO updateAudience(Long id, AudienceDTO dto) {
        Audience a = findAudienceOrThrow(id);
        a.setName(dto.getName());
        a.setDescription(dto.getDescription());
        a.setEstimatedSize(dto.getEstimatedSize());
        return toDto(audienceRepository.save(a));
    }

    @Transactional(readOnly = true)
    public AudienceDTO getById(Long id) {
        return toDto(findAudienceOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<AudienceDTO> getAll(Pageable pageable) {
        return audienceRepository.findAll(pageable).map(this::toDto);
    }

    public void deleteAudience(Long id) {
        audienceRepository.delete(findAudienceOrThrow(id));
    }

    public AudienceMemberDTO addMember(Long audienceId, AudienceMemberDTO dto) {
        Audience a = findAudienceOrThrow(audienceId);
        AudienceMember m = AudienceMember.builder()
                .audience(a)
                .userId(dto.getUserId())
                .email(dto.getEmail())
                .phone(dto.getPhone())
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .isActive(true)
                .build();
        AudienceMember saved = memberRepository.save(m);
        a.setEstimatedSize(a.getEstimatedSize() + 1);
        audienceRepository.save(a);
        return toMemberDto(saved);
    }

    public void removeMember(Long audienceId, Long memberId) {
        AudienceMember m = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + memberId));
        memberRepository.delete(m);
        Audience a = findAudienceOrThrow(audienceId);
        a.setEstimatedSize(Math.max(0, a.getEstimatedSize() - 1));
        audienceRepository.save(a);
    }

    @Transactional(readOnly = true)
    public Page<AudienceMemberDTO> getMembers(Long audienceId, Pageable pageable) {
        return memberRepository.findByAudienceId(audienceId, pageable).map(this::toMemberDto);
    }

    public List<AudienceMemberDTO> importMembers(Long audienceId, List<AudienceMemberDTO> dtos) {
        Audience a = findAudienceOrThrow(audienceId);
        List<AudienceMember> entities = dtos.stream().map(d -> AudienceMember.builder()
                .audience(a)
                .userId(d.getUserId())
                .email(d.getEmail())
                .phone(d.getPhone())
                .firstName(d.getFirstName())
                .lastName(d.getLastName())
                .isActive(true)
                .build()).collect(Collectors.toList());
        List<AudienceMember> saved = memberRepository.saveAll(entities);
        a.setEstimatedSize(a.getEstimatedSize() + saved.size());
        audienceRepository.save(a);
        return saved.stream().map(this::toMemberDto).collect(Collectors.toList());
    }

    private Audience findAudienceOrThrow(Long id) {
        return audienceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Audience not found: " + id));
    }

    private AudienceDTO toDto(Audience a) {
        return modelMapper.map(a, AudienceDTO.class);
    }

    private AudienceMemberDTO toMemberDto(AudienceMember m) {
        return AudienceMemberDTO.builder()
                .id(m.getId())
                .audienceId(m.getAudience() != null ? m.getAudience().getId() : null)
                .userId(m.getUserId())
                .email(m.getEmail())
                .phone(m.getPhone())
                .firstName(m.getFirstName())
                .lastName(m.getLastName())
                .isActive(m.isActive())
                .subscribedAt(m.getSubscribedAt())
                .unsubscribedAt(m.getUnsubscribedAt())
                .build();
    }
}
