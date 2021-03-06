package com.group8.meetingall.utils;

import com.group8.meetingall.entity.CustomRun;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HighlightUtil {
    static Logger logger = LoggerFactory.getLogger(HighlightUtil.class);

    public static String preProcessText(String text, List<String> words) {
        for (String word : words) {
            Pattern p = Pattern.compile(word);
            Matcher m = p.matcher(text);
            if (m.find()) {
                text = m.replaceAll("<" + word + "<");
            }
        }
        return text;
    }

    public static XWPFDocument createWord(String text, List<String> keywords,String subject) throws Exception {
        XWPFDocument document = new XWPFDocument();
        XWPFParagraph titleParagraph = document.createParagraph();
        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleParagraphRun = titleParagraph.createRun();
        titleParagraphRun.setText(StringUtils.isEmpty(subject) ? "Meeting Report" : subject);
        titleParagraphRun.setColor(Color.BLACK.getColor());
        titleParagraphRun.setFontSize(20);
        XWPFParagraph paragraph = document.createParagraph();
        String preProcessStr = preProcessText(text, keywords);
        List<CustomRun> customRuns = generateCustomRuns(preProcessStr, keywords);
        generateHighLightRuns(paragraph, customRuns);
        document.createParagraph();
        XWPFParagraph keywordParagraph = document.createParagraph();
        XWPFRun run1 = keywordParagraph.createRun();
        run1.setText("高频词： ");
        keywords.forEach(keyword -> {
            XWPFRun run = keywordParagraph.createRun();
            if (keywords.indexOf(keyword) == keywords.size() - 1) {
                run.setText(keyword);
            } else {
                run.setText(keyword + "， ");
            }
        });
        return document;
    }

    private static void generateHighLightRuns(XWPFParagraph paragraph, List<CustomRun> customRuns) {
        customRuns.forEach(customRun -> {
            XWPFRun run = paragraph.createRun();
            run.setText(customRun.getText());
            if (customRun.isHighlight()) {
                logger.info(customRun.getText());
                CTShd ctShd = run.getCTR().addNewRPr().addNewShd();
                ctShd.setFill(Color.YELLOW.getColor());
            }
        });
    }

    public static List<CustomRun> generateCustomRuns(String text, List<String> keywords) {
        List<String> keys = new ArrayList<>(Arrays.asList(text.split("<")));
        List<CustomRun> customRunList = new ArrayList<>();
        for (String key : keys) {
            CustomRun customRun = new CustomRun(key);
            if (keywords.contains(key)) {
                customRun.setHighlight(true);
            }
            customRunList.add(customRun);
        }
        customRunList.removeIf(customRun -> customRun.getText().isEmpty());
        return customRunList;
    }
}
