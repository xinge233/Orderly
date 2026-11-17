package com.orderly.host.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiPromptFactoryTest {

    @Test
    void injectsCurrentLocalTimeZoneAndStructuredTimeRule() {
        AiPromptFactory factory = new AiPromptFactory(new ObjectMapper());
        ZonedDateTime now = ZonedDateTime.of(
                2026,
                11,
                17,
                21,
                30,
                0,
                0,
                ZoneId.of("Asia/Shanghai")
        );

        String prompt = factory.systemPrompt(List.of(), false, now);

        assertThat(prompt)
                .contains("当前本地日期：2026-11-17")
                .contains("当前本地时间：2026-11-17T21:30+08:00")
                .contains("当前时区：Asia/Shanghai")
                .contains("2026-11-17T21:00:00+08:00")
                .contains("日期或时间含义不明确时必须返回 QUESTION");
    }
}
