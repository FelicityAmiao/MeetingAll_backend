package com.group8.meetingall.service;

import com.group8.meetingall.dto.MeetingDto;
import com.group8.meetingall.entity.MeetingProfile;
import com.group8.meetingall.exception.ASRException;
import com.group8.meetingall.repository.MeetingRepository;
import com.group8.meetingall.utils.JsonUtils;
import com.group8.meetingall.vo.MeetingRecordVo;
import com.group8.meetingall.vo.MeetingVo;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.group8.meetingall.utils.Constant.*;
import static com.group8.meetingall.utils.DateTimeUtil.getCurrentDateTime;
import static com.group8.meetingall.utils.DateTimeUtil.getMeetingDuration;
import static java.util.Objects.nonNull;

@Service
@Slf4j
public class MyMeetingService {
    @Autowired
    XFCantoneseASRService xfCantoneseASRService;
    @Autowired
    HighFrequencyService highFrequencyService;
    Logger logger = LoggerFactory.getLogger(MyMeetingService.class);
    @Autowired
    private MeetingRepository meetingRepository;
    @Autowired
    private XFChineseASRService XFChineseAsrService;
    @Value("${filePath.audio}")
    private String audioPath;
    @Autowired
    private SimpMessageSendingOperations simpMessageSendingOperations;

    public MeetingVo getActiveMeeting(String userId) {
        MeetingProfile meeting = meetingRepository.findActiveMeetingByUserId(userId);
        if (nonNull(meeting)) {
            return convertToMeetingVo(meeting);
        }
        return null;
    }

    public MeetingVo upsertMeeting(MeetingDto meetingDto) {
        MeetingProfile meetingProfile = new MeetingProfile();
        if (ObjectUtils.isEmpty(meetingDto.getMeetingId())) {
            meetingProfile = createMeeting(meetingDto);
            updateOtherMeetingToInActive(meetingDto.getUserId());
        } else {
            BeanUtils.copyProperties(meetingDto, meetingProfile);
        }
        boolean flag = meetingRepository.upsertMeeting(meetingProfile);
        if (flag) {
            return convertToMeetingVo(meetingProfile);
        }
        return null;
    }

    private void updateOtherMeetingToInActive(String userId) {
        MeetingProfile meeting = meetingRepository.findActiveMeetingByUserId(userId);
        if (nonNull(meeting)) {
            meeting.setActive(false);
            meetingRepository.upsertMeeting(meeting);
        }
    }

    private MeetingVo convertToMeetingVo(MeetingProfile m) {
        MeetingVo meetingVo = new MeetingVo();
        BeanUtils.copyProperties(m, meetingVo);
        return meetingVo;
    }

    private MeetingProfile createMeeting(MeetingDto meetingDto) {
        MeetingProfile meetingProfile = new MeetingProfile();
        BeanUtils.copyProperties(meetingDto, meetingProfile);
        meetingProfile.setMeetingId(UUID.randomUUID().toString());
        String currentDateTime = getCurrentDateTime();
        meetingProfile.setCreateTime(currentDateTime);
        meetingProfile.setStartDate(currentDateTime.substring(0, 10));
        meetingProfile.setStartTime(currentDateTime.substring(11));
        meetingProfile.setStatus(NEW);
        meetingProfile.setActive(true);
        meetingProfile.setFinish(false);
        return meetingProfile;
    }

    public List<MeetingRecordVo> getMeetingRecords(String user) {
        List<MeetingRecordVo> meetingRecordVoList = new ArrayList<>();
        List<MeetingProfile> meetingProfiles = meetingRepository.findHistoryMeetings(user);
        for (MeetingProfile meetingProfile : meetingProfiles) {
            MeetingRecordVo meetingRecordVo = new MeetingRecordVo();
            BeanUtils.copyProperties(meetingProfile, meetingRecordVo);
            meetingRecordVoList.add(meetingRecordVo);
        }
        return meetingRecordVoList.stream()
                .sorted(Comparator.comparing(MeetingRecordVo::getCreateTime).reversed())
                .collect(Collectors.toList());
    }

    public MeetingVo generateReport(String meetingId) throws ASRException {
        log.info("开始生成报告...");
        MeetingProfile meeting = meetingRepository.findMeetingByMeetingId(meetingId);
        if (meeting == null) {
            return null;
        }
        if (ObjectUtils.isEmpty(meeting.getAudioAddress())) {
            throw new ASRException("录音地址不存在");
        }
        meeting.setStatus(REPORT_ING);
        String fileName = generateReportName(meeting);
        switch (meeting.getLanguage()) {
            case 1:
                CompletableFuture.supplyAsync(() -> {
                    String uuid = XFChineseAsrService.convert(meeting.getAudioAddress());
                    generateWordFile(meeting, fileName, uuid);
                    return fileName;
                });
                meetingRepository.upsertMeeting(meeting);
                return convertToMeetingVo(meeting);
            case 2:
                CompletableFuture.supplyAsync(() -> {
                    String audioAddress = meeting.getAudioAddress();
                    String audioName = audioAddress.substring(0, audioAddress.indexOf("."));
                    transformVideoToPCMFormat(audioName);
                    String uuid = null;
                    try {
                        uuid = xfCantoneseASRService.startXFASRProcessing(audioName + ".pcm");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    generateWordFile(meeting, fileName, uuid);
                    return fileName;
                });
                boolean flag = meetingRepository.upsertMeeting(meeting);
                logger.info("update meeting flag: " + flag);
                return convertToMeetingVo(meeting);
            default:
                break;
        }
        return convertToMeetingVo(meeting);
    }

    private void transformVideoToPCMFormat(String audioName) {
        ProcessBuilder pb = new ProcessBuilder("/home/meetingall/files/script/transformToPCM.sh", audioName);
        Process process = null;
        try {
            process = pb.start();
            log.info("开始处理脚本...");
            int exitValue = process.waitFor();
            log.info("处理脚本结束,exit value is " + exitValue);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void generateWordFile(MeetingProfile meeting, String fileName, String uuid) {
        highFrequencyService.generateHighlightWordFile(uuid, fileName, meeting.getSubject());
        meeting.setReportAddress(fileName);
        meeting.setStatus(REPORT_FINISHED);
        MeetingRecordVo meetingRecordVo = new MeetingRecordVo();
        meetingRecordVo.setMeetingId(meeting.getMeetingId());
        meetingRecordVo.setReportAddress(meeting.getReportAddress());
        meetingRecordVo.setStatus(meeting.getStatus());
        meetingRepository.upsertMeeting(meeting);
        log.info("generate the word file success!");
        simpMessageSendingOperations.convertAndSend("/queue/reportGeneration", JsonUtils.toJson(meetingRecordVo));
        log.info("send success!");
    }

    private String generateReportName(MeetingProfile meetingProfile) {
        String reportName = "Meeting Report " + meetingProfile.getSubject() + SPACE + meetingProfile.getRoom().get(0) + SPACE + meetingProfile.getRoom().get(1) + SPACE + meetingProfile.getStartDate() + ".docx";
        reportName = reportName.replaceAll(SPACE, UNDERLINE);
        return reportName;
    }

    public boolean recording(MeetingProfile meeting) {
        try {
            Update update = new Update();
            update.set("status", "录音中");
            meetingRepository.findByIdAndUpdate(meeting.getMeetingId(), update);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String saveVoiceRecord(MultipartFile uploadFile, String meetingId, String meetingSubject) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        String format = sdf.format(new Date());
        File folder = new File(audioPath);
        if (!folder.isDirectory()) {
            folder.mkdir();
        }
        String newName = meetingSubject + "-" + format + ".wav";

        try {
            uploadFile.transferTo(new File(folder, newName));
            Update update = new Update();
            update.set("audioAddress", newName);
            update.set("status", "已录音");
            meetingRepository.findByIdAndUpdate(meetingId, update);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean finishMeeting(String meetingId) {
        MeetingProfile meeting = meetingRepository.findMeetingByMeetingId(meetingId);
        if (meeting == null) {
            return false;
        }
        meeting.setFinish(true);
        meeting.setActive(false);
        String endTime = getCurrentDateTime().substring(11);
        meeting.setEndTime(endTime);
        meeting.setDuration(getMeetingDuration(meeting.getStartDate(), meeting.getStartTime(), meeting.getEndTime()));
        return meetingRepository.upsertMeeting(meeting);
    }
}
